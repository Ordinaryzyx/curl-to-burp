package curl2burp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 第二段：参数数组 → RequestSpec。
 * 这里只做「curl 会怎么理解这些参数」的语义化，不碰报文细节。
 *
 * 选项分三类：
 * 1. 影响报文的 → 生效（见下面的 if 链）
 * 2. 不影响报文但 curl 文档里有的 → 忽略并进提示栏（HANDLED_BY_CURL_ONLY / VALUE_IGNORED_LONG）
 * 3. 会改变报文却没法在本工具里复现的（-K 配置文件、cookie jar、-F name=&lt;file）→ 报错中止
 * 未收录的选项按第 2 类处理，但提示栏会加 ⚠，避免「静默忽略导致报文缺东西」。
 */
public final class Options {

    /** multipart 边界。固定值：随机边界会让黄金用例无法断言，也让两次转换结果不一致。 */
    public static final String BOUNDARY = "----curl2burpBoundary7MA4YWxkTrZu0gW";

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** 影响报文、需要值的短选项。 */
    private static final String SUPPORTED_VALUE_SHORT = "XHdFTubAerC";
    /** 对报文无影响、需要值的短选项。 */
    private static final String IGNORED_VALUE_SHORT = "comwxDEPUQtyYz";
    /** 对报文无影响、无值的短选项。 */
    private static final String IGNORED_FLAG_SHORT = "012346#BfghijJlMnNOpqRsSvVZkL";

    private static final LinkedHashSet<String> IGNORED_FLAG_LONG = new LinkedHashSet<String>();
    private static final LinkedHashSet<String> IGNORED_VALUE_LONG = new LinkedHashSet<String>();
    /** 需要挑战-响应的认证方式：报文里不会有 Authorization。 */
    private static final LinkedHashSet<String> CHALLENGE_AUTH_LONG = new LinkedHashSet<String>();

    static {
        // curl 8.x 里对「发什么」没有影响的长选项（无值）
        String[] flags = {
                "--insecure", "--location", "--location-trusted", "--verbose", "--silent",
                "--show-error", "--include", "--no-buffer", "--http0.9", "--http1.0",
                "--http1.1", "--http2", "--http2-prior-knowledge", "--http3", "--http3-only",
                "--tlsv1", "--tlsv1.0", "--tlsv1.1", "--tlsv1.2", "--tlsv1.3",
                "--ssl", "--ssl-reqd", "--ssl-allow-beast", "--no-sessionid", "--no-ssl-sesscache",
                "--raw", "--no-keepalive", "--no-alpn", "--alpn", "--tcp-nodelay",
                "--no-tcp-nodelay", "--tcp-fastopen", "--default", "--ipv4", "--ipv6",
                "--progress-bar", "--no-progress-meter", "--no-clobber", "--remove-on-error",
                "--fail", "--fail-early", "--fail-with-body", "--globoff", "--use-ascii",
                "--disable", "--junk-session-cookies", "--remote-time", "--remote-name",
                "--remote-name-all", "--remote-header-name", "--create-dirs", "--xattr",
                "--no-xattr", "--retry-connrefused", "--retry-all-errors", "--parallel",
                "--parallel-immediate", "--metalink", "--list-only", "--manual", "--next",
                "--netrc", "--netrc-optional", "--no-netrc", "--trace-time", "--trace-ids",
                "--proxy-anyauth", "--proxy-basic", "--proxy-digest", "--proxy-ntlm",
                "--proxy-negotiate", "--proxy-tlsv1", "--socks5-basic", "--socks5-gssapi",
                "--compressed-ssh", "--no-compressed-ssh", "--basic", "--ca-native",
                "--no-ca-native", "--styled-output", "--no-styled-output"
        };
        for (String f : flags) {
            IGNORED_FLAG_LONG.add(f);
        }
        // curl 8.x 里对「发什么」没有影响的长选项（带值）
        String[] values = {
                "--output", "--output-dir", "--max-time", "--connect-timeout", "--retry",
                "--retry-delay", "--retry-max-time", "--max-redirs", "--cookie-jar",
                "--resolve", "--connect-to", "--interface", "--proxy", "--proxy-user",
                "--proxy-header", "--noproxy", "--preproxy", "--socks4", "--socks4a",
                "--socks5", "--socks5-hostname", "--cacert", "--capath", "--cert",
                "--cert-type", "--key", "--key-type", "--pass", "--pubkey", "--pinnedpubkey",
                "--hostpubmd5", "--hostpubsha256", "--ciphers", "--tls13-ciphers", "--tls-max",
                "--write-out", "--dump-header", "--stderr", "--trace", "--trace-ascii",
                "--libcurl", "--limit-rate", "--speed-limit", "--speed-time", "--max-filesize",
                "--expect100-timeout", "--happy-eyeballs-timeout-ms", "--keepalive-time",
                "--dns-servers", "--dns-interface", "--dns-ipv4-addr", "--dns-ipv6-addr",
                "--local-port", "--proto", "--proto-redir", "--proto-default", "--parallel-max",
                "--unix-socket", "--abstract-unix-socket", "--time-cond", "--rate",
                "--etag-save", "--etag-compare", "--create-file-mode", "--netrc-file",
                "--engine", "--egd-file", "--random-file", "--telnet-option", "--ftp-port",
                "--ftp-account", "--ftp-alternative-to-user", "--ftp-method",
                "--ftp-ssl-ccc-mode", "--mail-from", "--mail-rcpt", "--mail-auth",
                "--sasl-authzid", "--krb", "--delegation", "--service-name", "--login-options",
                "--aws-sigv4", "--haproxy-clientip"
        };
        for (String v : values) {
            IGNORED_VALUE_LONG.add(v);
        }
        String[] challenge = {"--digest", "--ntlm", "--ntlm-wb", "--negotiate", "--anyauth"};
        for (String c : challenge) {
            CHALLENGE_AUTH_LONG.add(c);
        }
    }

    /** multipart 的一个字段。-F 会解释 @file，--form-string 不会。 */
    private static final class FormPart {
        final String value;
        final boolean literal;

        FormPart(String value, boolean literal) {
            this.value = value;
            this.literal = literal;
        }
    }

    private Options() {
    }

    public static RequestSpec parse(List<String> tokens) throws ParseException {
        RequestSpec spec = new RequestSpec();

        int start = 0;
        if (!tokens.isEmpty() && isCurlPrefix(tokens.get(0))) {
            start = 1;
        }

        String method = null;
        String requestTarget = null;
        List<String> rawHeaders = new ArrayList<String>();
        List<String> dataPieces = new ArrayList<String>();
        List<String> queryPieces = new ArrayList<String>();
        List<FormPart> formParts = new ArrayList<FormPart>();
        String jsonData = null;
        String uploadFile = null;
        boolean get = false;
        boolean head = false;
        boolean compressed = false;
        String userPass = null;
        String oauth2 = null;
        String authScheme = null;
        String cookie = null;
        String userAgent = null;
        String referer = null;
        String range = null;
        List<String> urls = new ArrayList<String>();
        LinkedHashSet<String> ignored = new LinkedHashSet<String>();

        int[] idx = new int[]{start};
        while (idx[0] < tokens.size()) {
            String tok = tokens.get(idx[0]);
            int pos = idx[0] + 1;
            if (tok == null || tok.isEmpty()) {
                idx[0]++;
                continue;
            }

            if (tok.startsWith("--")) {
                String name = tok;
                String inline = null;
                int eq = tok.indexOf('=');
                if (eq > 0) {
                    name = tok.substring(0, eq);
                    inline = tok.substring(eq + 1);
                }

                if ("--config".equals(name)) {
                    throw new ParseException("第 " + pos + " 个参数 `" + name
                            + "` 不支持：curl 配置文件不在本工具范围内", pos);
                }

                // ---- 影响报文 ----
                if ("--request".equals(name)) {
                    method = valueOrNext(tokens, idx, name, inline);
                } else if ("--request-target".equals(name)) {
                    requestTarget = valueOrNext(tokens, idx, name, inline);
                } else if ("--header".equals(name)) {
                    rawHeaders.add(valueOrNext(tokens, idx, name, inline));
                } else if ("--data".equals(name) || "--data-ascii".equals(name)) {
                    dataPieces.add(resolveData(valueOrNext(tokens, idx, name, inline), true, true));
                } else if ("--data-binary".equals(name)) {
                    dataPieces.add(resolveData(valueOrNext(tokens, idx, name, inline), false, true));
                } else if ("--data-raw".equals(name)) {
                    dataPieces.add(resolveData(valueOrNext(tokens, idx, name, inline), false, false));
                } else if ("--data-urlencode".equals(name)) {
                    dataPieces.add(resolveDataUrlencode(valueOrNext(tokens, idx, name, inline)));
                } else if ("--url-query".equals(name)) {
                    queryPieces.add(resolveDataUrlencode(valueOrNext(tokens, idx, name, inline)));
                } else if ("--json".equals(name)) {
                    if (jsonData != null) {
                        spec.note("出现多个 --json，只取最后一个");
                    }
                    jsonData = resolveData(valueOrNext(tokens, idx, name, inline), false, true);
                } else if ("--form".equals(name)) {
                    formParts.add(new FormPart(valueOrNext(tokens, idx, name, inline), false));
                } else if ("--form-string".equals(name)) {
                    formParts.add(new FormPart(valueOrNext(tokens, idx, name, inline), true));
                } else if ("--upload-file".equals(name)) {
                    uploadFile = nonStdin(valueOrNext(tokens, idx, name, inline), name);
                } else if ("--user".equals(name)) {
                    userPass = valueOrNext(tokens, idx, name, inline);
                } else if ("--oauth2-bearer".equals(name)) {
                    oauth2 = valueOrNext(tokens, idx, name, inline);
                } else if ("--cookie".equals(name)) {
                    cookie = valueOrNext(tokens, idx, name, inline);
                } else if ("--user-agent".equals(name)) {
                    userAgent = valueOrNext(tokens, idx, name, inline);
                } else if ("--referer".equals(name)) {
                    referer = valueOrNext(tokens, idx, name, inline);
                } else if ("--range".equals(name)) {
                    range = rangeValue(valueOrNext(tokens, idx, name, inline));
                } else if ("--continue-at".equals(name)) {
                    String v = valueOrNext(tokens, idx, name, inline);
                    if (!"-".equals(v)) {
                        range = v + "-";
                    }
                } else if ("--url".equals(name)) {
                    urls.add(valueOrNext(tokens, idx, name, inline));
                } else if ("--get".equals(name)) {
                    get = true;
                } else if ("--head".equals(name)) {
                    head = true;
                } else if ("--compressed".equals(name)) {
                    compressed = true;
                } else if ("--path-as-is".equals(name)) {
                    spec.note("--path-as-is: 本工具默认就不规范化路径，无需该选项");

                // ---- 不影响报文：忽略 ----
                } else if (CHALLENGE_AUTH_LONG.contains(name)) {
                    authScheme = name;
                    ignored.add(name);
                } else if (IGNORED_FLAG_LONG.contains(name)) {
                    ignored.add(name);
                } else if (IGNORED_VALUE_LONG.contains(name)) {
                    String v = valueOrNext(tokens, idx, name, inline);
                    ignored.add(name);
                    if ("--resolve".equals(name) || "--connect-to".equals(name)) {
                        spec.note(name + " 把域名钉到了指定 IP；Burp 按它自己的 DNS/Host 映射发，"
                                + "要固定 IP 请在 Burp 里配映射");
                    }
                    if (v == null) {
                        throw new ParseException("选项 " + name + " 缺少参数值", pos);
                    }

                // ---- 未收录：不阻断，但要显著提示 ----
                } else {
                    String swallowed = null;
                    if (inline == null && idx[0] + 1 < tokens.size()) {
                        String nxt = tokens.get(idx[0] + 1);
                        if (!nxt.startsWith("-") && !looksLikeUrl(nxt)) {
                            idx[0]++;
                            swallowed = nxt;
                        }
                    }
                    spec.note("⚠ 未收录的选项 `" + name + (swallowed == null ? "" : " " + swallowed)
                            + "` 已忽略，若它影响报文请手工确认");
                }
                idx[0]++;
                continue;
            }

            if (tok.length() > 1 && tok.charAt(0) == '-') {
                String cluster = tok.substring(1);
                int k = 0;
                while (k < cluster.length()) {
                    char ch = cluster.charAt(k);
                    if (SUPPORTED_VALUE_SHORT.indexOf(ch) >= 0) {
                        String v;
                        if (k + 1 < cluster.length()) {
                            v = cluster.substring(k + 1);
                        } else {
                            v = nextValue(tokens, idx, "-" + ch, pos);
                        }
                        if (ch == 'X') {
                            method = v;
                        } else if (ch == 'H') {
                            rawHeaders.add(v);
                        } else if (ch == 'd') {
                            dataPieces.add(resolveData(v, true, true));
                        } else if (ch == 'F') {
                            formParts.add(new FormPart(v, false));
                        } else if (ch == 'T') {
                            uploadFile = nonStdin(v, "-T");
                        } else if (ch == 'u') {
                            userPass = v;
                        } else if (ch == 'b') {
                            cookie = v;
                        } else if (ch == 'A') {
                            userAgent = v;
                        } else if (ch == 'e') {
                            referer = v;
                        } else if (ch == 'r') {
                            range = rangeValue(v);
                        } else if (ch == 'C') {
                            if (!"-".equals(v)) {
                                range = v + "-";
                            }
                        }
                        k = cluster.length();
                        continue;
                    }
                    if (ch == 'G') {
                        get = true;
                        k++;
                        continue;
                    }
                    if (ch == 'I') {
                        head = true;
                        k++;
                        continue;
                    }
                    if (IGNORED_VALUE_SHORT.indexOf(ch) >= 0) {
                        if (k + 1 >= cluster.length()) {
                            nextValue(tokens, idx, "-" + ch, pos);
                        }
                        ignored.add("-" + ch);
                        k = cluster.length();
                        continue;
                    }
                    if (ch == 'K') {
                        throw new ParseException("第 " + pos + " 个参数 `-K` 不支持：curl 配置文件不在本工具范围内", pos);
                    }
                    if (IGNORED_FLAG_SHORT.indexOf(ch) >= 0) {
                        ignored.add("-" + ch);
                        k++;
                        continue;
                    }
                    spec.note("⚠ 未收录的选项 `-" + ch + "` 已忽略，若它影响报文请手工确认");
                    k = cluster.length();
                }
                idx[0]++;
                continue;
            }

            urls.add(tok);
            idx[0]++;
        }

        if (urls.isEmpty()) {
            throw new ParseException("没有找到 URL：命令里需要一个请求地址");
        }
        int urlish = 0;
        String url = null;
        for (String u : urls) {
            if (looksLikeUrl(u)) {
                urlish++;
                if (url == null) {
                    url = u;
                }
            }
        }
        if (url == null) {
            url = urls.get(0);
        }
        int candidateCount = urlish > 0 ? urlish : urls.size();
        if (candidateCount > 1) {
            spec.note("发现 " + candidateCount + " 个 URL，只处理第一个");
        }

        // ---- 头：按 -H 的原始顺序落位 ----
        for (String raw : rawHeaders) {
            applyHeader(spec, raw);
        }

        // ---- -G / --url-query：data 拼到 query，不要 body ----
        List<String> q = new ArrayList<String>();
        if (get) {
            q.addAll(dataPieces);
            if (jsonData != null) {
                q.add(jsonData);
            }
        }
        q.addAll(queryPieces);
        if (!q.isEmpty()) {
            url = url + (url.indexOf('?') >= 0 ? "&" : "?") + join(q, "&");
        }

        // ---- body ----
        boolean hasBody = false;
        String bodyText = null;
        byte[] bodyBytes = null;

        if (!get) {
            if (!formParts.isEmpty()) {
                if (!dataPieces.isEmpty() || jsonData != null || uploadFile != null) {
                    spec.note("同时出现 -F 与其他 body 选项，按 -F 处理，其余已舍弃");
                }
                bodyText = buildMultipart(formParts);
                hasBody = true;
                addAuto(spec, "Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
            } else if (uploadFile != null) {
                if (!dataPieces.isEmpty() || jsonData != null) {
                    spec.note("同时出现 -T 与 -d/--json，按 -T 处理，其余已舍弃");
                }
                bodyBytes = readAllBytes(uploadFile);
                hasBody = true;
            } else if (jsonData != null) {
                if (!dataPieces.isEmpty()) {
                    spec.note("同时出现 --json 与 -d，按 --json 处理，-d 已舍弃");
                }
                bodyText = jsonData;
                hasBody = true;
                addAuto(spec, "Content-Type", "application/json");
                addAuto(spec, "Accept", "application/json");
            } else if (!dataPieces.isEmpty()) {
                bodyText = join(dataPieces, "&");
                hasBody = true;
            }
        }

        // ---- 由选项推导的补头 ----
        if (oauth2 != null) {
            addAuto(spec, "Authorization", "Bearer " + oauth2);
        } else if (userPass != null && authScheme == null) {
            // curl 语义：没有冒号时密码为空
            String cred = userPass.indexOf(':') >= 0 ? userPass : userPass + ":";
            addAuto(spec, "Authorization", "Basic " + base64(cred.getBytes(UTF8)));
        } else if (userPass != null) {
            spec.note(authScheme + " 是挑战-响应认证，报文里不会带 Authorization（curl 第一次请求也不带）");
        }
        if (cookie != null) {
            if (looksLikeCookieFile(cookie)) {
                throw new ParseException("`-b " + cookie
                        + "` 看起来是 cookie 文件，本工具不支持 cookie jar；请直接粘贴 Cookie 头的值");
            }
            addAuto(spec, "Cookie", cookie);
        }
        if (userAgent != null) {
            addAuto(spec, "User-Agent", userAgent);
        }
        if (referer != null) {
            addAuto(spec, "Referer", referer);
        }
        if (range != null) {
            addAuto(spec, "Range", range);
        }
        if (compressed) {
            addAuto(spec, "Accept-Encoding", "gzip, deflate, br");
        }

        // ---- 方法 ----
        String m = method;
        if (m == null || m.isEmpty()) {
            if (head) {
                m = "HEAD";
            } else if (uploadFile != null && !get) {
                m = "PUT";
            } else if (hasBody) {
                m = "POST";
            } else {
                m = "GET";
            }
        }
        if (head) {
            // -I 无 body
            hasBody = false;
            bodyText = null;
            bodyBytes = null;
        }

        spec.method = m;
        spec.url = url;
        spec.requestTarget = requestTarget;
        spec.hasBody = hasBody;
        spec.bodyText = bodyText;
        spec.bodyBytes = bodyBytes;

        if (!ignored.isEmpty()) {
            spec.note("已忽略选项: " + join(new ArrayList<String>(ignored), ", "));
        }
        if (ignored.contains("-k") || ignored.contains("--insecure")) {
            spec.note("-k: 是否校验证书由 Burp 的 TLS 设置决定，与报文无关");
        }
        if (ignored.contains("-L") || ignored.contains("--location")) {
            spec.note("-L: Burp Repeater 不会自动跟随重定向");
        }
        if (ignored.contains("-x") || ignored.contains("--proxy")) {
            spec.note("-x/--proxy: 本工具不发包；若你原本指向 Burp，直接在 Burp 里发即可");
        }
        return spec;
    }

    // ---------------------------------------------------------------- 头

    private static void applyHeader(RequestSpec spec, String raw) throws ParseException {
        if (raw == null) {
            return;
        }
        // -H "Name;" → 值为空的头
        if (raw.endsWith(";") && raw.indexOf(':') < 0) {
            String name = raw.substring(0, raw.length() - 1).trim();
            if (name.isEmpty()) {
                return;
            }
            spec.headers.add(new String[]{name, ""});
            return;
        }
        int c = raw.indexOf(':');
        if (c < 0) {
            spec.note("忽略无法解析的头: " + raw);
            return;
        }
        String name = raw.substring(0, c).trim();
        if (name.isEmpty()) {
            spec.note("忽略无法解析的头: " + raw);
            return;
        }
        String value = raw.substring(c + 1);
        int v0 = 0;
        while (v0 < value.length() && (value.charAt(v0) == ' ' || value.charAt(v0) == '\t')) {
            v0++;
        }
        value = value.substring(v0);
        if (value.isEmpty()) {
            // -H "Name:" → 该头不发送
            spec.suppressed.add(name.toLowerCase());
            return;
        }
        spec.headers.add(new String[]{name, value});
    }

    private static void addAuto(RequestSpec spec, String name, String value) {
        if (spec.isSuppressed(name) || spec.hasHeader(name)) {
            return;
        }
        spec.headers.add(new String[]{name, value});
    }

    // ---------------------------------------------------------------- body

    private static String resolveData(String value, boolean stripNewlines, boolean allowFile)
            throws ParseException {
        if (value == null) {
            return null;
        }
        if (allowFile && value.startsWith("@")) {
            String path = value.substring(1);
            if (path.isEmpty() || "-".equals(path)) {
                throw new ParseException("`" + value + "` 不支持：本工具不从标准输入读 body");
            }
            return readTextFile(path, stripNewlines);
        }
        return value;
    }

    private static String resolveDataUrlencode(String value) throws ParseException {
        if (value == null) {
            return null;
        }
        if (value.startsWith("@")) {
            throw new ParseException("--data-urlencode / --url-query 的 `@file` 形式暂不支持");
        }
        int eq = value.indexOf('=');
        if (eq < 0) {
            return urlEncode(value);
        }
        String name = value.substring(0, eq);
        String content = value.substring(eq + 1);
        if (name.isEmpty()) {
            return urlEncode(content);
        }
        return name + "=" + urlEncode(content);
    }

    private static String buildMultipart(List<FormPart> parts) throws ParseException {
        StringBuilder sb = new StringBuilder();
        for (FormPart part : parts) {
            String raw = part.value == null ? "" : part.value;
            int eq = raw.indexOf('=');
            if (eq <= 0) {
                throw new ParseException("-F \"" + raw + "\" 无法理解，应为 name=value 或 name=@file");
            }
            String name = raw.substring(0, eq);
            String rest = raw.substring(eq + 1);

            String type = null;
            String filename = null;
            int mark = modifierIndex(rest);
            String value = rest;
            if (mark >= 0) {
                value = rest.substring(0, mark);
                String[] mods = rest.substring(mark).split(";");
                for (String mod : mods) {
                    String t = mod.trim();
                    if (t.regionMatches(true, 0, "type=", 0, 5)) {
                        type = t.substring(5);
                    } else if (t.regionMatches(true, 0, "filename=", 0, 9)) {
                        filename = t.substring(9);
                    }
                }
            }

            sb.append("--").append(BOUNDARY).append("\r\n");
            if (!part.literal && value.startsWith("<")) {
                throw new ParseException("不支持 -F \"" + raw + "\"：从文件读取字段值的变体 v1 不实现");
            }
            if (!part.literal && value.startsWith("@")) {
                String path = value.substring(1);
                File f = new File(path);
                if (!f.isFile()) {
                    throw new ParseException("multipart 文件读取失败: " + path);
                }
                String fn = filename != null ? filename : f.getName();
                String ct = type != null ? type : guessContentType(fn);
                sb.append("Content-Disposition: form-data; name=\"").append(name)
                        .append("\"; filename=\"").append(fn).append("\"\r\n");
                sb.append("Content-Type: ").append(ct).append("\r\n");
                sb.append("\r\n");
                sb.append(readTextFile(path, false));
                sb.append("\r\n");
            } else {
                sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n");
                sb.append("\r\n");
                sb.append(value);
                sb.append("\r\n");
            }
        }
        sb.append("--").append(BOUNDARY).append("--\r\n");
        return sb.toString();
    }

    /** 返回 ;type= / ;filename= / ;headers= 中最先出现的位置，没有则 -1。 */
    private static int modifierIndex(String s) {
        int best = -1;
        String[] markers = {";type=", ";filename=", ";headers="};
        String lower = s.toLowerCase();
        for (String mk : markers) {
            int at = lower.indexOf(mk);
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        return best;
    }

    private static String guessContentType(String filename) {
        String n = filename.toLowerCase();
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 ? n.substring(dot + 1) : "";
        if ("txt".equals(ext) || "log".equals(ext)) {
            return "text/plain";
        }
        if ("json".equals(ext)) {
            return "application/json";
        }
        if ("html".equals(ext) || "htm".equals(ext)) {
            return "text/html";
        }
        if ("xml".equals(ext)) {
            return "application/xml";
        }
        if ("csv".equals(ext)) {
            return "text/csv";
        }
        if ("js".equals(ext)) {
            return "application/javascript";
        }
        if ("css".equals(ext)) {
            return "text/css";
        }
        if ("png".equals(ext)) {
            return "image/png";
        }
        if ("jpg".equals(ext) || "jpeg".equals(ext)) {
            return "image/jpeg";
        }
        if ("gif".equals(ext)) {
            return "image/gif";
        }
        if ("pdf".equals(ext)) {
            return "application/pdf";
        }
        if ("zip".equals(ext)) {
            return "application/zip";
        }
        return "application/octet-stream";
    }

    /**
     * curl 的判定：`-b` 的值里只要有 = 就是 cookie 串；没有 = 且像路径/以 .txt 结尾才当 jar 文件。
     */
    private static boolean looksLikeCookieFile(String value) {
        String v = value.trim();
        if (v.indexOf('=') >= 0) {
            return false;
        }
        if (v.endsWith(".txt")) {
            return true;
        }
        return v.indexOf('/') >= 0 || v.indexOf('\\') >= 0;
    }

    // ---------------------------------------------------------------- 工具

    private static String rangeValue(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        String t = v.trim();
        if (t.toLowerCase().startsWith("bytes=")) {
            return t;
        }
        return "bytes=" + t;
    }

    private static String nonStdin(String v, String opt) throws ParseException {
        if ("-".equals(v)) {
            throw new ParseException("`" + opt + " -` 不支持：本工具不从标准输入读 body");
        }
        return v;
    }

    private static String valueOrNext(List<String> tokens, int[] idx, String name, String inline)
            throws ParseException {
        if (inline != null) {
            return inline;
        }
        return nextValue(tokens, idx, name, idx[0] + 1);
    }

    private static String nextValue(List<String> tokens, int[] idx, String name, int pos)
            throws ParseException {
        if (idx[0] + 1 >= tokens.size()) {
            throw new ParseException("第 " + pos + " 个参数 `" + name + "` 缺少参数值", pos);
        }
        idx[0]++;
        return tokens.get(idx[0]);
    }

    private static boolean isCurlPrefix(String t) {
        if (t == null || t.isEmpty()) {
            return false;
        }
        String s = t;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        s = s.toLowerCase();
        return "curl".equals(s) || "curl.exe".equals(s);
    }

    /** 用来从「URL 候选」里挑出真正像地址的那个，避免被未知选项的值顶掉。 */
    static boolean looksLikeUrl(String t) {
        if (t == null || t.isEmpty()) {
            return false;
        }
        String l = t.toLowerCase();
        if (l.contains("://")) {
            return true;
        }
        if (l.startsWith("http") || l.startsWith("www.") || l.startsWith("localhost")
                || l.startsWith("[")) {
            return true;
        }
        int slash = l.indexOf('/');
        String host = slash >= 0 ? l.substring(0, slash) : l;
        if (host.indexOf(' ') >= 0 || host.isEmpty()) {
            return false;
        }
        return host.indexOf('.') > 0;
    }

    static String readTextFile(String path, boolean stripNewlines) throws ParseException {
        byte[] all = readAllBytes(path);
        if (stripNewlines) {
            int w = 0;
            for (int r = 0; r < all.length; r++) {
                if (all[r] != '\r' && all[r] != '\n') {
                    all[w++] = all[r];
                }
            }
            byte[] trimmed = new byte[w];
            System.arraycopy(all, 0, trimmed, 0, w);
            all = trimmed;
        }
        return new String(all, UTF8);
    }

    static byte[] readAllBytes(String path) throws ParseException {
        File f = new File(path);
        if (!f.isFile()) {
            throw new ParseException("读取文件失败（不存在或不是文件）: " + path);
        }
        try {
            return Files.readAllBytes(f.toPath());
        } catch (IOException e) {
            throw new ParseException("读取文件失败: " + path + " (" + e.getMessage() + ")");
        }
    }

    private static String base64(byte[] data) {
        return java.util.Base64.getEncoder().encodeToString(data);
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(UTF8)) {
            int c = b & 0xFF;
            boolean keep = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
            if (keep) {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
            }
        }
        return sb.toString();
    }
}
