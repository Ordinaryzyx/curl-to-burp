package curl2burp;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 反向转换：Burp 里的原始 HTTP 报文 → curl 命令。
 * 与 CurlToHttp 互为反向，都是纯函数、不依赖 Swing。
 *
 * 目标不是「生成一条能跑的优雅命令」，而是「生成一条再跑一遍正向转换能还原出同一份报文的命令」。
 * 输出**固定单行**：\\ 续行在 cmd / PowerShell 里会被当成多条命令执行，
 * 粘到本地终端会直接报错，所以宁可一行长一点。
 */
public final class HttpToCurl {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** METHOD SP target SP HTTP/x.y */
    private static final Pattern REQUEST_LINE =
            Pattern.compile("^([A-Za-z][A-Za-z0-9_\\-]*)[ \\t]+(\\S+)[ \\t]+HTTP/(\\d(?:\\.\\d)?)[ \\t]*$");
    /** 代理风格请求行：GET http://host/path HTTP/1.1 */
    private static final Pattern ABSOLUTE_FORM = Pattern.compile("^[A-Za-z][A-Za-z0-9+.\\-]*://");

    private HttpToCurl() {
    }

    public static String toCurl(String rawHttp, List<String> notesOut) throws ParseException {
        return toCurl(rawHttp, null, notesOut);
    }

    public static String toCurl(String rawHttp, Config cfg, List<String> notesOut)
            throws ParseException {
        List<String> notes = new ArrayList<String>();
        String result = convert(rawHttp, cfg == null ? Config.defaults() : cfg, notes);
        if (notesOut != null) {
            notesOut.addAll(notes);
        }
        return result;
    }

    private static String convert(String rawHttp, Config cfg, List<String> notes) throws ParseException {
        if (rawHttp == null || rawHttp.trim().isEmpty()) {
            throw new ParseException("没有可解析的报文");
        }
        // 不能把整段文本的 \r\n 归一成 \n：body 里的 CRLF 属于内容（multipart 尤其依赖它），必须原样保留
        String text = rawHttp;
        int n = text.length();
        int p = 0;
        while (p < n && (text.charAt(p) == '\n' || text.charAt(p) == '\r')) {
            p = skipLineBreak(text, p);
        }
        if (p >= n) {
            throw new ParseException("没有可解析的报文");
        }

        int lineEnd = lineEndOf(text, p);
        String requestLine = text.substring(p, lineEnd).trim();
        Matcher m = REQUEST_LINE.matcher(requestLine);
        if (!m.matches()) {
            throw new ParseException("第一行不是请求行（应为 METHOD /path HTTP/1.1）: " + requestLine);
        }
        String method = m.group(1);
        String target = m.group(2);

        // ---- 头 ----
        List<String[]> headers = new ArrayList<String[]>();
        String body = "";
        int cursor = skipLineBreak(text, lineEnd);
        while (cursor < n) {
            int e = lineEndOf(text, cursor);
            String line = text.substring(cursor, e);
            int next = skipLineBreak(text, e);
            if (line.isEmpty()) {
                body = text.substring(next);
                break;
            }
            if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && !headers.isEmpty()) {
                // 老的折行头：接到上一行
                headers.get(headers.size() - 1)[1] += " " + line.trim();
                cursor = next;
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new ParseException("第 " + lineNo(text, cursor) + " 行的头无法解析: " + line);
            }
            headers.add(new String[]{line.substring(0, colon).trim(), line.substring(colon + 1).trim()});
            cursor = next;
        }

        // ---- body：有 Content-Length 就按它截，报文里的末尾换行不该混进 body ----
        String clValue = headerValue(headers, "Content-Length");
        byte[] rawBody = body.getBytes(UTF8);
        int bodyLen = rawBody.length;
        boolean clMatches = false;
        if (clValue != null) {
            int cl;
            try {
                cl = Integer.parseInt(clValue.trim());
            } catch (NumberFormatException ex) {
                throw new ParseException("Content-Length 不是数字: " + clValue);
            }
            if (cl < 0) {
                throw new ParseException("Content-Length 不能为负: " + clValue);
            }
            clMatches = cl == bodyLen;
            if (cl < bodyLen) {
                // 被截掉的部分如果只是末尾换行/空格，就是编辑器噪音，不提示
                String tail = new String(rawBody, cl, bodyLen - cl, UTF8);
                body = new String(rawBody, 0, cl, UTF8);
                if (!tail.trim().isEmpty()) {
                    notes.add("按 Content-Length 把 body 截到 " + cl + " 字节（原文 " + bodyLen + " 字节）");
                }
            } else if (cl > bodyLen) {
                notes.add("Content-Length 声明 " + cl + " 字节，实际只有 " + bodyLen + " 字节，已按实际内容生成");
            }
        }
        boolean hasBody = !body.isEmpty();
        if ("HEAD".equalsIgnoreCase(method) && hasBody) {
            notes.add("HEAD 报文带了 body，已忽略 body（HEAD 不发 body）");
            hasBody = false;
            body = "";
        }

        // ---- URL ----
        String url;
        String targetOverride = null;
        boolean keepHostHeader = false;
        if (ABSOLUTE_FORM.matcher(target).find()) {
            url = target;
            if (headerValue(headers, "Host") != null) {
                notes.add("请求行里已是绝对地址，Host 头已并入 URL");
            }
        } else {
            String host = headerValue(headers, "Host");
            if (host == null || host.isEmpty()) {
                throw new ParseException("报文里没有 Host 头，无法还原 URL；请先补一个 Host 头");
            }
            String port = portOf(host);
            String scheme;
            if ("443".equals(port)) {
                scheme = "https";
            } else if ("80".equals(port)) {
                scheme = "http";
            } else {
                scheme = cfg.reverseHttps ? "https" : "http";
                notes.add("报文里没写端口和 scheme，按当前设置生成 " + scheme
                        + "://（要换协议在左侧命令行里改，或取消选项栏的 HTTPS）");
            }
            boolean isDefaultPort = ("https".equals(scheme) && "443".equals(port))
                    || ("http".equals(scheme) && "80".equals(port));
            if (isDefaultPort) {
                // Host 里显式写了默认端口：curl 会把它省掉，所以额外用 -H 保留，做到报文完全一致
                keepHostHeader = true;
                notes.add("Host 里显式写了默认端口，已额外用 -H '" + host + "' 保留");
            }
            String hostForUrl = isDefaultPort ? stripPort(host) : host;
            if (target.startsWith("/")) {
                url = scheme + "://" + hostForUrl + target;
            } else {
                // CONNECT host:port、OPTIONS * 这类非 / 开头的 target
                url = scheme + "://" + hostForUrl + "/";
                targetOverride = target;
            }
        }

        // ---- 组装 ----
        boolean useHead = "HEAD".equalsIgnoreCase(method) && !hasBody;
        boolean needMethod = !useHead
                && !(("GET".equalsIgnoreCase(method) && !hasBody)
                || ("POST".equalsIgnoreCase(method) && hasBody));

        StringBuilder sb = new StringBuilder("curl");
        if (useHead) {
            sb.append(" -I");
        } else if (needMethod) {
            sb.append(" -X ").append(method);
        }
        sb.append(' ').append(quote(url));
        if (targetOverride != null) {
            sb.append(" --request-target ").append(quote(targetOverride));
        }

        for (String[] h : headers) {
            if ("Host".equalsIgnoreCase(h[0]) && !keepHostHeader) {
                continue;
            }
            if ("Content-Length".equalsIgnoreCase(h[0]) && clMatches) {
                // 与实际长度一致，交给 curl 自己算
                continue;
            }
            if ("Content-Length".equalsIgnoreCase(h[0])) {
                notes.add("保留了手工 Content-Length: " + h[1] + "（与 body 实际长度不一致）");
            }
            // 空值头必须写成 Name; 的形式，否则正向转换会当成「删除该头」
            String value = h[1].isEmpty() ? h[0] + ";" : h[0] + ": " + h[1];
            sb.append(" -H ").append(quote(value));
        }
        if (hasBody) {
            sb.append(" --data-raw ").append(quoteBody(body));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 工具

    private static int lineEndOf(String s, int from) {
        int i = from;
        while (i < s.length() && s.charAt(i) != '\n' && s.charAt(i) != '\r') {
            i++;
        }
        return i;
    }

    /** 只吞掉一个换行序列（\r\n / \n / \r），不吞后面的空行，否则 body 开头的空行会被吃掉。 */
    private static int skipLineBreak(String s, int at) {
        if (at >= s.length()) {
            return at;
        }
        char c = s.charAt(at);
        if (c == '\r') {
            return (at + 1 < s.length() && s.charAt(at + 1) == '\n') ? at + 2 : at + 1;
        }
        if (c == '\n') {
            return at + 1;
        }
        return at;
    }

    private static String headerValue(List<String[]> headers, String name) {
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase(name)) {
                return h[1];
            }
        }
        return null;
    }

    private static String portOf(String host) {
        if (host.startsWith("[")) {
            int rb = host.indexOf(']');
            if (rb > 0 && rb + 1 < host.length() && host.charAt(rb + 1) == ':') {
                return host.substring(rb + 2);
            }
            return "";
        }
        int colon = host.lastIndexOf(':');
        if (colon > 0) {
            return host.substring(colon + 1);
        }
        return "";
    }

    private static String stripPort(String host) {
        if (host.startsWith("[")) {
            int rb = host.indexOf(']');
            return rb > 0 ? host.substring(0, rb + 1) : host;
        }
        int colon = host.lastIndexOf(':');
        return colon > 0 ? host.substring(0, colon) : host;
    }

    private static int lineNo(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** 单引号包裹；内含单引号时用 '\'' 断开，bash/cmd 之外的 POSIX 写法都能解析回来。 */
    static String quote(String s) {
        if (s == null || s.isEmpty()) {
            return "''";
        }
        if (s.indexOf('\'') < 0) {
            return "'" + s + "'";
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 含换行/制表符时用 $'...'，否则单引号；和 Chrome 的 bash 导出形态一致。 */
    static String quoteBody(String body) {
        boolean needsAnsi = body.indexOf('\n') >= 0 || body.indexOf('\r') >= 0
                || body.indexOf('\t') >= 0;
        if (!needsAnsi) {
            return quote(body);
        }
        StringBuilder sb = new StringBuilder("$'");
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\'') {
                sb.append("\\'");
            } else if (c < 0x20 || c == 0x7F) {
                sb.append("\\x").append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
            } else {
                sb.append(c);
            }
        }
        return sb.append('\'').toString();
    }
}
