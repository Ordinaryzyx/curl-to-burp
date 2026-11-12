package curl2burp;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第三段：RequestSpec → 报文字符串。
 * 只有这里知道 Content-Length、CRLF、空行这些报文级细节。
 */
public final class Builder {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.\\-]*)://");
    private static final Pattern CHARSET_IN_CT =
            Pattern.compile("(?i)charset\\s*=\\s*\"?([^\"\\s;]+)\"?");

    private Builder() {
    }

    public static String build(RequestSpec spec, Config cfg) throws ParseException {
        if (spec.url == null || spec.url.trim().isEmpty()) {
            throw new ParseException("没有找到 URL：命令里需要一个请求地址");
        }
        String ls = cfg.crlf ? "\r\n" : "\n";
        Url url = parseUrl(spec.url);

        List<String[]> out = new ArrayList<String[]>();
        boolean userHost = false;
        for (String[] h : spec.headers) {
            if (h[0].equalsIgnoreCase("Host")) {
                userHost = true;
                break;
            }
        }
        if (!userHost) {
            out.add(new String[]{"Host", url.hostPort});
        }
        for (String[] h : spec.headers) {
            out.add(h);
        }
        if (url.userInfo != null && !hasHeader(out, "Authorization")) {
            out.add(new String[]{"Authorization", "Basic "
                    + java.util.Base64.getEncoder().encodeToString(url.userInfo.getBytes(UTF8))});
            spec.note("URL 里的 userinfo 已转为 Authorization: Basic");
        }

        // body 编码 = Content-Type 里的 charset，否则 UTF-8
        Charset cs = resolveCharset(out, spec);
        byte[] bodyBytes = null;
        String bodyOut = null;
        if (spec.hasBody) {
            if (spec.bodyBytes != null) {
                bodyBytes = spec.bodyBytes;
                bodyOut = new String(bodyBytes, cs);
            } else {
                bodyOut = spec.bodyText == null ? "" : spec.bodyText;
                bodyBytes = bodyOut.getBytes(cs);
            }
            spec.note("body 按 " + cs.name() + " 编码");
        }
        int bodyLen = bodyBytes == null ? 0 : bodyBytes.length;

        // Content-Length 策略
        String explicitCl = null;
        String transferEncoding = null;
        for (String[] h : out) {
            if (h[0].equalsIgnoreCase("Content-Length")) {
                explicitCl = h[1];
            } else if (h[0].equalsIgnoreCase("Transfer-Encoding")) {
                transferEncoding = h[1];
            }
        }
        boolean chunked = transferEncoding != null
                && transferEncoding.toLowerCase().contains("chunked");

        if (explicitCl != null) {
            spec.note("使用了手工 Content-Length(" + explicitCl + ")，实际 body 为 "
                    + bodyLen + " 字节，可能不一致");
        } else if (chunked) {
            spec.note("检测到 Transfer-Encoding: chunked，未生成 chunk 分块编码，body 以原始形式附在报文后");
        } else if (spec.hasBody && cfg.autoContentLength) {
            out.add(new String[]{"Content-Length", String.valueOf(bodyLen)});
        } else if (spec.hasBody) {
            spec.note("已关闭自动 Content-Length，报文里不写该头");
        }

        StringBuilder sb = new StringBuilder();
        String target = spec.requestTarget == null || spec.requestTarget.isEmpty()
                ? url.requestTarget
                : sanitizeRequestTarget(spec.requestTarget);
        sb.append(spec.method).append(' ').append(target)
                .append(" HTTP/1.1").append(ls);
        for (String[] h : out) {
            sb.append(h[0]).append(": ").append(h[1]).append(ls);
        }
        sb.append(ls);
        if (bodyOut != null) {
            sb.append(bodyOut);
        }
        return sb.toString();
    }

    private static boolean hasHeader(List<String[]> headers, String name) {
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static Charset resolveCharset(List<String[]> headers, RequestSpec spec) {
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase("Content-Type")) {
                Matcher m = CHARSET_IN_CT.matcher(h[1]);
                if (m.find()) {
                    String name = m.group(1).trim();
                    try {
                        return Charset.forName(name);
                    } catch (Exception e) {
                        spec.note("Content-Type 里的 charset `" + name + "` 无法识别，已回退 UTF-8");
                        return UTF8;
                    }
                }
            }
        }
        return UTF8;
    }

    // ---------------------------------------------------------------- URL

    /** 解析结果：请求行里的 path?query，以及 Host 头该用的值。 */
    static final class Url {
        String hostPort;
        String requestTarget;
        String userInfo;
    }

    static Url parseUrl(String raw) throws ParseException {
        String s = raw.trim();
        int frag = s.indexOf('#');
        if (frag >= 0) {
            s = s.substring(0, frag);
        }
        String scheme = "http";
        Matcher m = SCHEME.matcher(s);
        if (m.find()) {
            scheme = m.group(1).toLowerCase();
            s = s.substring(m.end());
        }
        if (s.isEmpty()) {
            throw new ParseException("URL 里没有主机名: " + raw);
        }

        String authority;
        String remainder;
        int cut = indexOfAny(s, "/?");
        if (cut < 0) {
            authority = s;
            remainder = "";
        } else {
            authority = s.substring(0, cut);
            remainder = s.charAt(cut) == '?' ? "/" + s.substring(cut) : s.substring(cut);
        }

        String userInfo = null;
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            userInfo = authority.substring(0, at);
            authority = authority.substring(at + 1);
        }

        String host = authority;
        String port = null;
        if (authority.startsWith("[")) {
            int rb = authority.indexOf(']');
            if (rb > 0) {
                host = authority.substring(0, rb + 1);
                if (rb + 1 < authority.length() && authority.charAt(rb + 1) == ':') {
                    port = authority.substring(rb + 2);
                }
            }
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon > 0 && isDigits(authority.substring(colon + 1))) {
                host = authority.substring(0, colon);
                port = authority.substring(colon + 1);
            }
        }
        if (host.isEmpty()) {
            throw new ParseException("URL 里没有主机名: " + raw);
        }
        if (port != null && isDefaultPort(scheme, port)) {
            port = null;
        }

        Url u = new Url();
        u.hostPort = port == null ? host : host + ":" + port;
        u.userInfo = userInfo;
        u.requestTarget = sanitizeRequestTarget(remainder.isEmpty() ? "/" : remainder);
        return u;
    }

    /**
     * 请求行里的 path 不能有裸空格和控制字符，只把它们百分号编码。
     * 其余一律原样：/a/../b、//x、/%2e%2e/ 都不做规范化。
     */
    private static String sanitizeRequestTarget(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') {
                sb.append("%20");
            } else if (c < 0x20 || c == 0x7F) {
                sb.append('%')
                        .append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isDefaultPort(String scheme, String port) {
        if ("http".equals(scheme) || "ws".equals(scheme)) {
            return "80".equals(port);
        }
        if ("https".equals(scheme) || "wss".equals(scheme)) {
            return "443".equals(port);
        }
        return false;
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static int indexOfAny(String s, String chars) {
        int best = -1;
        for (int i = 0; i < chars.length(); i++) {
            int at = s.indexOf(chars.charAt(i));
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        return best;
    }
}
