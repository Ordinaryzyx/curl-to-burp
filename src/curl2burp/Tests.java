package curl2burp;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 无依赖断言集。只测外部可观察行为：curl 进 → 报文出。
 * 跑法：java -cp out curl2burp.Tests
 */
public final class Tests {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String CRLF = "\r\n";

    private static int pass = 0;
    private static int fail = 0;
    private static String notes = "";

    private interface Case {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, "UTF-8"));

        // ---- 1~6 请求行与 URL ----
        test("1 最小 GET", new Case() {
            public void run() throws Exception {
                checkEquals("1 最小 GET",
                        conv("curl http://example.com"),
                        "GET / HTTP/1.1" + CRLF + "Host: example.com" + CRLF + CRLF);
            }
        });
        test("2 补 http://", new Case() {
            public void run() throws Exception {
                checkContains("2 补 scheme", conv("curl example.com/a"), "GET /a HTTP/1.1" + CRLF);
            }
        });
        test("3 非默认端口", new Case() {
            public void run() throws Exception {
                checkContains("3 非默认端口", conv("curl http://a.com:8443/x"), "Host: a.com:8443");
            }
        });
        test("4 默认端口省略", new Case() {
            public void run() throws Exception {
                checkContains("4 默认端口", conv("curl http://a.com:80/x"), "Host: a.com" + CRLF);
            }
        });
        test("5 不规范化路径", new Case() {
            public void run() throws Exception {
                checkContains("5 路径原样", conv("curl http://a.com/a/../b//c"),
                        "GET /a/../b//c HTTP/1.1");
            }
        });
        test("6 剥离 fragment", new Case() {
            public void run() throws Exception {
                checkEquals("6 fragment",
                        conv("curl \"http://a.com/p?x=1#frag\""),
                        "GET /p?x=1 HTTP/1.1" + CRLF + "Host: a.com" + CRLF + CRLF);
            }
        });

        // ---- 7~13 方法、body、Content-Length ----
        test("7 -X PUT", new Case() {
            public void run() throws Exception {
                checkContains("7 -X", conv("curl -X PUT http://a.com/x"), "PUT /x HTTP/1.1");
            }
        });
        test("8 多个 -d 用 & 连接", new Case() {
            public void run() throws Exception {
                String r = conv("curl -d 'a=1' -d 'b=2' http://a.com/x");
                checkContains("8 POST", r, "POST /x HTTP/1.1");
                checkContains("8 body", r, CRLF + CRLF + "a=1&b=2");
                checkEquals("8 CL", headerValue(r, "Content-Length"), "7");
            }
        });
        test("9 -X 优先于 -d 推断", new Case() {
            public void run() throws Exception {
                String r = conv("curl -X GET -d 'a=1' http://a.com/x");
                checkContains("9 GET 带 body", r, "GET /x HTTP/1.1");
                checkContains("9 body", r, CRLF + CRLF + "a=1");
            }
        });
        test("10 -G 拼 query", new Case() {
            public void run() throws Exception {
                checkEquals("10 -G",
                        conv("curl -G -d 'q=1' http://a.com/s"),
                        "GET /s?q=1 HTTP/1.1" + CRLF + "Host: a.com" + CRLF + CRLF);
            }
        });
        test("11 -G 合并已有 query", new Case() {
            public void run() throws Exception {
                checkContains("11 -G 合并", conv("curl -G -d 'q=1' \"http://a.com/s?a=2\""),
                        "GET /s?a=2&q=1 HTTP/1.1");
            }
        });
        test("12 --data-raw", new Case() {
            public void run() throws Exception {
                String r = conv("curl -H 'Content-Type: application/json' --data-raw '{\"a\":1}' http://a.com/x");
                checkContains("12 body", r, CRLF + CRLF + "{\"a\":1}");
                checkEquals("12 CL", headerValue(r, "Content-Length"), "7");
            }
        });
        test("13 Content-Length 按字节数", new Case() {
            public void run() throws Exception {
                String body = "{\"名\":\"张三\"}";
                String r = conv("curl -H 'Content-Type: application/json' --data-raw '"
                        + body + "' http://a.com/x");
                checkEquals("13 CL=字节数", headerValue(r, "Content-Length"), "16");
                checkEquals("13 CL=实际 body", headerValue(r, "Content-Length"),
                        String.valueOf(bodyByteLen(r)));
            }
        });

        // ---- 14~23 补头与选项 ----
        test("14 --json", new Case() {
            public void run() throws Exception {
                String r = conv("curl --json '{}' http://a.com/x");
                checkContains("14 CT", r, "Content-Type: application/json");
                checkContains("14 Accept", r, "Accept: application/json");
            }
        });
        test("15 -u Basic", new Case() {
            public void run() throws Exception {
                checkContains("15 Basic", conv("curl -u admin:p@ss http://a.com/x"),
                        "Authorization: Basic YWRtaW46cEBzcw==");
            }
        });
        test("16 -b Cookie", new Case() {
            public void run() throws Exception {
                checkContains("16 Cookie", conv("curl -b 'a=1; b=2' http://a.com/x"),
                        "Cookie: a=1; b=2");
            }
        });
        test("17 -A / -e", new Case() {
            public void run() throws Exception {
                String r = conv("curl -A 'UA/1' -e 'http://r/' http://a.com/x");
                checkContains("17 UA", r, "User-Agent: UA/1");
                checkContains("17 Referer", r, "Referer: http://r/");
            }
        });
        test("18 -H 'X-Foo:' 删头", new Case() {
            public void run() throws Exception {
                checkNotContains("18 删头", conv("curl -H 'X-Foo:' http://a.com/x"), "X-Foo");
            }
        });
        test("19 -H 'X-Foo;' 空值头", new Case() {
            public void run() throws Exception {
                checkContains("19 空值头", conv("curl -H 'X-Foo;' http://a.com/x"),
                        "X-Foo: " + CRLF);
            }
        });
        test("20 -H Host 生效且不重复", new Case() {
            public void run() throws Exception {
                String r = conv("curl -H 'Host: evil.com' http://a.com/x");
                checkContains("20 Host", r, "Host: evil.com");
                checkEquals("20 Host 只出现一次", String.valueOf(countOf(r, "Host:")), "1");
            }
        });
        test("21 手工 Content-Length 不被覆盖", new Case() {
            public void run() throws Exception {
                String r = conv("curl -H 'Content-Length: 999' -d 'a=1' http://a.com/x");
                checkContains("21 保留 999", r, "Content-Length: 999");
                checkTrue("21 提示不一致", notes.contains("不一致"), notes);
            }
        });
        test("22 --compressed", new Case() {
            public void run() throws Exception {
                checkContains("22 AE", conv("curl --compressed http://a.com/x"),
                        "Accept-Encoding: gzip, deflate, br");
            }
        });
        test("23 -I", new Case() {
            public void run() throws Exception {
                checkEquals("23 HEAD", conv("curl -I http://a.com/x"),
                        "HEAD /x HTTP/1.1" + CRLF + "Host: a.com" + CRLF + CRLF);
            }
        });

        // ---- 24 multipart ----
        final File dir = new File(System.getProperty("java.io.tmpdir"), "curl2burp-test");
        dir.mkdirs();
        final File aTxt = new File(dir, "a.txt");
        Files.write(aTxt.toPath(), "hello".getBytes(UTF8));

        test("24 multipart", new Case() {
            public void run() throws Exception {
                String r = conv("curl -F 'k=v' -F 'f=@" + aTxt.getAbsolutePath() + "' http://a.com/u");
                checkContains("24 boundary 头", r,
                        "Content-Type: multipart/form-data; boundary=" + Options.BOUNDARY);
                checkContains("24 普通字段", r, "name=\"k\"");
                checkContains("24 文件字段", r, "name=\"f\"; filename=\"a.txt\"");
                checkContains("24 结束边界", r, "--" + Options.BOUNDARY + "--");
                checkEquals("24 CL=实际 body", headerValue(r, "Content-Length"),
                        String.valueOf(bodyByteLen(r)));
            }
        });

        // ---- 25~27 三种导出方言 ----
        test("25 Chrome bash ($'...')", new Case() {
            public void run() throws Exception {
                String r = conv("curl 'http://a.com/x' \\\n"
                        + "  -H 'content-type: application/json' \\\n"
                        + "  --data-raw $'{\\n  \"a\": 1\\n}'");
                String body = bodyOf(r);
                checkTrue("25 真换行", body.contains("\n  \"a\": 1\n"), body);
                checkNotContains("25 无字面 \\n", body, "\\n");
                checkEquals("25 CL=实际 body", headerValue(r, "Content-Length"),
                        String.valueOf(bodyByteLen(r)));
            }
        });
        test("26 Chrome cmd (^ 续行 / ^\")", new Case() {
            public void run() throws Exception {
                String[] variants = {
                        // 整条参数用普通引号，内嵌引号写成 ^"
                        "curl \"http://a.com/x\" ^\n"
                                + "  -H \"x-foo: bar\" ^\n"
                                + "  --data-raw \"^{^\"a^\":1^}\"",
                        // 内嵌引号写成 ^\^"
                        "curl \"http://a.com/x\" ^\n"
                                + "  -H \"x-foo: bar\" ^\n"
                                + "  --data-raw \"^{^\\^\"a^\\^\":1^}^\"",
                        // 整条参数用 ^" 定界
                        "curl ^\"http://a.com/x^\" ^\n"
                                + "  -H ^\"x-foo: bar^\" ^\n"
                                + "  --data-raw ^\"^{^\\^\"a^\\^\":1^}^\""
                };
                for (int k = 0; k < variants.length; k++) {
                    String r = conv(variants[k]);
                    String tag = "26 [" + (k + 1) + "] ";
                    checkContains(tag + "请求行", r, "POST /x HTTP/1.1");
                    checkContains(tag + "头值", r, "x-foo: bar");
                    checkContains(tag + "body", r, CRLF + CRLF + "{\"a\":1}");
                    checkEquals(tag + "CL", headerValue(r, "Content-Length"), "7");
                }
                checkEquals("26 自动判为 cmd", notes.contains("按 Windows cmd 方言解析") ? "y" : "n", "y");
            }
        });
        test("27 PowerShell 单引号", new Case() {
            public void run() throws Exception {
                String r = conv("curl -d '{\"a\":1}' http://a.com/x");
                checkContains("27 body", r, CRLF + CRLF + "{\"a\":1}");
            }
        });

        // ---- 28~34 容错与诊断 ----
        test("28 一行 vs 续行", new Case() {
            public void run() throws Exception {
                String one = conv("curl 'http://a.com/x' -H 'X-A: 1' --data-raw 'a=1'");
                String multi = conv("curl 'http://a.com/x' \\\n  -H 'X-A: 1' \\\n  --data-raw 'a=1'");
                checkEquals("28 一致", multi, one);
            }
        });
        test("29 curl 前缀变体", new Case() {
            public void run() throws Exception {
                String a = conv("/usr/bin/curl http://a.com/x");
                String b = conv("http://a.com/x");
                checkEquals("29 无前缀一致", b, a);
            }
        });
        test("30 忽略的选项进提示", new Case() {
            public void run() throws Exception {
                String r = conv("curl -kL -v -m 30 -s http://a.com/x");
                checkContains("30 报文正常", r, "GET /x HTTP/1.1");
                checkTrue("30 提示含 -k", notes.contains("-k"), notes);
                checkTrue("30 提示含 -L", notes.contains("-L"), notes);
                checkTrue("30 提示含 -v", notes.contains("-v"), notes);
                checkTrue("30 提示含 -m", notes.contains("-m"), notes);
                checkTrue("30 提示含 -s", notes.contains("-s"), notes);
            }
        });
        test("31 多个 URL", new Case() {
            public void run() throws Exception {
                String r = conv("curl http://a.com/x http://b.com/y");
                checkContains("31 只处理第一个", r, "Host: a.com");
                checkTrue("31 提示 URL 数", notes.contains("2 个 URL"), notes);
            }
        });
        test("32 未收录选项不阻断但要提示", new Case() {
            public void run() throws Exception {
                String r = conv("curl --badopt http://a.com");
                checkContains("32 报文照常生成", r, "GET / HTTP/1.1");
                checkContains("32 提示含选项名", notes, "--badopt");
                checkFails("32 -K 仍然报错", "curl -K cfg.txt http://a.com", "-K");
            }
        });
        test("33 引号未闭合", new Case() {
            public void run() {
                checkFails("33 引号未闭合", "curl -d 'unclosed http://a.com", "个字符");
            }
        });
        test("34 空输入", new Case() {
            public void run() {
                checkFails("34 空输入", "", "没有可解析的命令");
                checkFails("34 只有空白", "  \n\t  \n", "没有可解析的命令");
            }
        });

        // ---- 35~ 真实场景与长命令（用户实际粘贴的形态） ----
        test("35 生产环境一条（-sk --resolve -o -w）", new Case() {
            public void run() throws Exception {
                String cmd = "curl -sk --resolve gjy.example.com:443:203.0.113.56 \\\n"
                        + "-H 'X-Forwarded-For: 127.0.0.1' \\\n"
                        + "https://gjy.example.com/portal/ -o index.html "
                        + "-w '%{http_code} %{size_download}\\n'";
                String r = conv(cmd);
                checkEquals("35 请求行", firstLine(r), "GET /portal/ HTTP/1.1");
                checkContains("35 Host", r, "Host: gjy.example.com");
                checkContains("35 XFF", r, "X-Forwarded-For: 127.0.0.1");
                checkNotContains("35 无 body", r, "Content-Length");
                checkTrue("35 提示 -w", notes.contains("-w"), notes);
                checkTrue("35 提示 --resolve", notes.contains("--resolve"), notes);
                checkTrue("35 提示 -o", notes.contains("-o"), notes);
            }
        });
        test("36 超长命令（百个头 + 大 body）", new Case() {
            public void run() throws Exception {
                StringBuilder sb = new StringBuilder("curl -X POST 'https://a.com/api/x?q=1'");
                for (int i = 0; i < 100; i++) {
                    sb.append(" -H 'X-H").append(i).append(": v").append(i).append("'");
                }
                StringBuilder body = new StringBuilder();
                for (int i = 0; i < 800; i++) {
                    body.append("ab");
                }
                sb.append(" --data-raw '").append(body).append("'");
                String cmd = sb.toString();
                checkTrue("36 命令够长", cmd.length() > 3000, "长度 " + cmd.length());
                String r = conv(cmd);
                checkContains("36 请求行", r, "POST /api/x?q=1 HTTP/1.1");
                checkContains("36 最后一个头", r, "X-H99: v99");
                checkEquals("36 CL", headerValue(r, "Content-Length"), "1600");
            }
        });
        test("37 PowerShell 反引号续行", new Case() {
            public void run() throws Exception {
                String cmd = "curl.exe \"https://a.com/api\" `\n"
                        + "  -H \"accept: application/json\" `\n"
                        + "  --data-raw '{\"a\":1}'";
                String r = conv(cmd);
                checkContains("37 请求行", r, "POST /api HTTP/1.1");
                checkContains("37 头", r, "accept: application/json");
                checkContains("37 body", r, CRLF + CRLF + "{\"a\":1}");
                checkTrue("37 判为 PowerShell", notes.contains("PowerShell"), notes);
            }
        });
        test("43 --url-query / --request-target", new Case() {
            public void run() throws Exception {
                checkContains("43 --url-query",
                        conv("curl --url-query 'a=1' http://a.com/x"), "GET /x?a=1 HTTP/1.1");
                checkContains("43 --request-target",
                        conv("curl --request-target '/../etc/passwd' http://a.com/x"),
                        "GET /../etc/passwd HTTP/1.1");
            }
        });
        test("44 --digest 时不生成 Basic", new Case() {
            public void run() throws Exception {
                String r = conv("curl --digest -u admin:pass http://a.com/x");
                checkNotContains("44 无 Authorization", r, "Authorization");
                checkTrue("44 有提示", notes.contains("挑战-响应"), notes);
            }
        });
        test("38 续行紧跟需要值的选项", new Case() {
            public void run() throws Exception {
                String r = conv("curl -d \\\n  'a=1' \\\n  http://a.com/x");
                checkContains("38 body 没被吞", r, CRLF + CRLF + "a=1");
            }
        });
        test("39 -r/--range 生成 Range 头", new Case() {
            public void run() throws Exception {
                checkContains("39 -r", conv("curl -r 0-100 http://a.com/x"),
                        "Range: bytes=0-100");
                checkContains("39 --range", conv("curl --range 0-100 http://a.com/x"),
                        "Range: bytes=0-100");
            }
        });
        test("40 --form-string 不解释 @", new Case() {
            public void run() throws Exception {
                String r = conv("curl --form-string 'k=@notafile' http://a.com/x");
                checkContains("40 字面值", r, "@notafile");
            }
        });
        test("41 -b 带 = 和 / 不误判为文件", new Case() {
            public void run() throws Exception {
                checkContains("41 cookie", conv("curl -b 'sid=a/b' http://a.com/x"), "Cookie: sid=a/b");
            }
        });
        test("42 未收录选项吃掉自己的值", new Case() {
            public void run() throws Exception {
                String r = conv("curl --some-new-opt somevalue https://a.com/x");
                checkEquals("42 URL 不被顶掉", firstLine(r), "GET /x HTTP/1.1");
                checkContains("42 值被吞掉", notes, "somevalue");
            }
        });
        // ---- 补充：不在黄金表里但容易回归的路径 ----
        final File bodyFile = new File(dir, "body.txt");
        Files.write(bodyFile.toPath(), "a=1\r\nb=2\n".getBytes(UTF8));

        test("补 -d @file 剔除 CRLF", new Case() {
            public void run() throws Exception {
                String r = conv("curl -d '@" + bodyFile.getAbsolutePath() + "' http://a.com/x");
                checkContains("补 -d @file", r, CRLF + CRLF + "a=1b=2");
            }
        });
        test("补 --data-binary @file 保留原样", new Case() {
            public void run() throws Exception {
                String r = conv("curl --data-binary '@" + bodyFile.getAbsolutePath() + "' http://a.com/x");
                checkEquals("补 --data-binary CL",
                        headerValue(r, "Content-Length"), String.valueOf("a=1\r\nb=2\n".getBytes(UTF8).length));
            }
        });
        test("补 --data-urlencode", new Case() {
            public void run() throws Exception {
                String r = conv("curl --data-urlencode 'a=b c' http://a.com/x");
                checkContains("补 urlencode", r, CRLF + CRLF + "a=b%20c");
            }
        });
        test("补 -T 上传文件", new Case() {
            public void run() throws Exception {
                String r = conv("curl -T '" + aTxt.getAbsolutePath() + "' http://a.com/u");
                checkContains("补 -T 方法", r, "PUT /u HTTP/1.1");
                checkContains("补 -T body", r, CRLF + CRLF + "hello");
                checkEquals("补 -T CL", headerValue(r, "Content-Length"), "5");
            }
        });
        test("补 LF 模式", new Case() {
            public void run() throws Exception {
                Config cfg = new Config();
                cfg.crlf = false;
                String r = CurlToHttp.convert("curl http://a.com", cfg);
                checkEquals("补 LF", r, "GET / HTTP/1.1\nHost: a.com\n\n");
            }
        });
        test("补 charset 无法识别回退", new Case() {
            public void run() throws Exception {
                conv("curl -H 'Content-Type: text/plain; charset=x-nope' -d 'a=1' http://a.com/x");
                checkTrue("补 charset 提示", notes.contains("回退 UTF-8"), notes);
            }
        });
        test("补 -b 文件报错", new Case() {
            public void run() {
                checkFails("补 -b file", "curl -b cookies.txt http://a.com/x", "cookie");
            }
        });
        test("补 -F '<file' 报错", new Case() {
            public void run() {
                checkFails("补 -F <file", "curl -F 'a=<x.txt' http://a.com/x", "不支持");
            }
        });
        test("补 Transfer-Encoding: chunked 不写 CL", new Case() {
            public void run() throws Exception {
                String r = conv("curl -H 'Transfer-Encoding: chunked' -d 'a=1' http://a.com/x");
                checkNotContains("补 chunked 无 CL", r, "Content-Length");
            }
        });
        test("补 -u 无冒号按空密码", new Case() {
            public void run() throws Exception {
                checkContains("补 -u 无冒号", conv("curl -u admin http://a.com/x"),
                        "Authorization: Basic YWRtaW46");
            }
        });
        test("补 --path-as-is 不报错", new Case() {
            public void run() throws Exception {
                checkContains("补 --path-as-is", conv("curl --path-as-is http://a.com/a/../b"),
                        "GET /a/../b HTTP/1.1");
            }
        });

        // ---- 46~ 反向：报文 → curl ----
        test("46 报文转 curl（最简）", new Case() {
            public void run() throws Exception {
                String packet = "GET /a/b?x=1 HTTP/1.1\r\nHost: a.com\r\nX-Foo: 1\r\n\r\n";
                checkEquals("46 最简", revHttp(packet),
                        "curl 'http://a.com/a/b?x=1' -H 'X-Foo: 1'");
            }
        });
        test("47 443 推断 https + 保留显式端口", new Case() {
            public void run() throws Exception {
                String packet = "POST /api HTTP/1.1\r\nHost: a.com:443\r\n"
                        + "Content-Type: application/json\r\nContent-Length: 7\r\n\r\n{\"a\":1}";
                checkEquals("47 https", rev(packet),
                        "curl 'https://a.com/api' -H 'Host: a.com:443'"
                                + " -H 'Content-Type: application/json' --data-raw '{\"a\":1}'");
            }
        });
        test("48 含换行的 body 用 $'...'", new Case() {
            public void run() throws Exception {
                String packet = "POST /x HTTP/1.1\r\nHost: a.com\r\nContent-Length: 3\r\n\r\na\nb\n";
                checkContains("48 $'...'", rev(packet), "--data-raw $'a\\nb'");
            }
        });
        test("49 含单引号的 body", new Case() {
            public void run() throws Exception {
                String packet = "POST /x HTTP/1.1\r\nHost: a.com\r\nContent-Length: 3\r\n\r\na'b";
                checkContains("49 单引号转义", rev(packet), "--data-raw 'a'\\''b'");
            }
        });
        test("50 Content-Length 不一致时保留手工值", new Case() {
            public void run() throws Exception {
                String packet = "POST /x HTTP/1.1\r\nHost: a.com\r\nContent-Length: 999\r\n\r\na=1";
                String curl = rev(packet);
                checkContains("50 保留 CL", curl, "-H 'Content-Length: 999'");
                checkContains("50 保留 body", curl, "--data-raw 'a=1'");
            }
        });
        test("51 报文缺 Host 报错", new Case() {
            public void run() {
                checkReverseFails("51 缺 Host", "GET /x HTTP/1.1\r\n\r\n", "Host");
                checkReverseFails("51 不是请求行", "hello world", "请求行");
                checkReverseFails("51 空报文", "   \n\n", "没有可解析的报文");
            }
        });

        // ---- 往返一致性：curl → 报文 → curl → 报文，两次报文必须完全相同 ----
        test("52 往返一致性", new Case() {
            public void run() throws Exception {
                roundTrip("52 最小 GET", "curl http://a.com");
                roundTrip("52 畸形路径", "curl http://a.com/a/../b//c");
                roundTrip("52 -X PUT", "curl -X PUT http://a.com/x");
                roundTrip("52 多个 -d", "curl -d 'a=1' -d 'b=2' http://a.com/x");
                roundTrip("52 -G", "curl -G -d 'q=1' \"http://a.com/s?a=2\"");
                roundTrip("52 空值头", "curl -H 'X-Foo;' http://a.com/x");
                roundTrip("52 自定义 Host", "curl -H 'Host: evil.com' http://a.com/x");
                roundTrip("52 --json", "curl --json '{}' http://a.com/x");
                roundTrip("52 -u", "curl -u admin:p@ss http://a.com/x");
                roundTrip("52 -b", "curl -b 'a=1; b=2' http://a.com/x");
                roundTrip("52 -A -e", "curl -A 'UA/1' -e 'http://r/' http://a.com/x");
                roundTrip("52 -I", "curl -I http://a.com/x");
                roundTrip("52 手工 CL", "curl -H 'Content-Length: 999' -d 'a=1' http://a.com/x");
                roundTrip("52 中文 body", "curl --data-raw '{\"名\":\"张三\"}' http://a.com/x");
                roundTrip("52 单引号 body", "curl --data-raw 'a'\\''b' http://a.com/x");
                roundTrip("52 multipart",
                        "curl -F 'k=v' -F 'f=@" + aTxt.getAbsolutePath() + "' http://a.com/u");
                roundTrip("52 生产那条",
                        "curl -sk --resolve gjy.example.com:443:203.0.113.56 \\\n"
                                + "-H 'X-Forwarded-For: 127.0.0.1' \\\n"
                                + "https://gjy.example.com/portal/ "
                                + "-o index.html -w '%{http_code}'");
            }
        });

        // ---- 53 反向输出必须是单行（多行粘到 cmd/PowerShell 会被当成多条命令） ----
        test("53 反向输出单行", new Case() {
            public void run() throws Exception {
                String[] packets = {
                        "GET /a HTTP/1.1\r\nHost: a.com\r\nX-A: 1\r\nX-B: 2\r\nX-C: 3\r\n\r\n",
                        "POST /x HTTP/1.1\r\nHost: a.com\r\nContent-Type: application/json\r\n"
                                + "Content-Length: 7\r\n\r\n{\"a\":1}",
                        "POST /u HTTP/1.1\r\nHost: a.com\r\nContent-Length: 5\r\n\r\na\nb\nc"
                };
                for (int k = 0; k < packets.length; k++) {
                    String curl = rev(packets[k]);
                    checkNotContains("53 [" + (k + 1) + "] 无换行", curl, "\n");
                    checkNotContains("53 [" + (k + 1) + "] 无续行符", curl, "\\\n");
                }
            }
        });

        println("");
        println("结果: " + pass + " 通过, " + fail + " 失败");
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 断言

    private static void test(String name, Case c) {
        try {
            c.run();
        } catch (Throwable t) {
            fail++;
            println("FAIL " + name + " — 异常: " + t);
        }
    }

    private static void checkEquals(String name, String actual, String expected) {
        if (actual == null ? expected == null : actual.equals(expected)) {
            pass++;
        } else {
            fail++;
            println("FAIL " + name + "\n  期望: " + show(expected) + "\n  实际: " + show(actual));
        }
    }

    private static void checkContains(String name, String haystack, String needle) {
        if (haystack != null && haystack.contains(needle)) {
            pass++;
        } else {
            fail++;
            println("FAIL " + name + "\n  缺少: " + show(needle) + "\n  实际: " + show(haystack));
        }
    }

    private static void checkNotContains(String name, String haystack, String needle) {
        if (haystack != null && !haystack.contains(needle)) {
            pass++;
        } else {
            fail++;
            println("FAIL " + name + "\n  不该出现: " + show(needle) + "\n  实际: " + show(haystack));
        }
    }

    private static void checkTrue(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
        } else {
            fail++;
            println("FAIL " + name + "\n  实际: " + show(detail));
        }
    }

    private static void checkFails(String name, String curl, String expectInMessage) {
        try {
            String r = conv(curl);
            fail++;
            println("FAIL " + name + " — 本应抛 ParseException，却得到报文: " + show(r));
        } catch (ParseException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains(expectInMessage)) {
                pass++;
            } else {
                fail++;
                println("FAIL " + name + "\n  消息应含: " + expectInMessage + "\n  实际消息: " + msg);
            }
        }
    }

    // ---------------------------------------------------------------- 工具

    private static String conv(String curl) throws ParseException {
        List<String> out = new ArrayList<String>();
        String r = CurlToHttp.convert(curl, new Config(), out);
        notes = join(out, "；");
        return r;
    }

    private static String rev(String packet) throws ParseException {
        return HttpToCurl.toCurl(packet, null);
    }

    private static String revHttp(String packet) throws ParseException {
        Config cfg = new Config();
        cfg.reverseHttps = false;
        return HttpToCurl.toCurl(packet, cfg, null);
    }

    /** curl → 报文 → curl → 报文，两次报文必须完全一致。 */
    private static void roundTrip(String name, String curl) throws ParseException {
        String p1 = conv(curl);
        String c2 = HttpToCurl.toCurl(p1, null);
        String p2 = conv(c2);
        if (p2.equals(p1)) {
            pass++;
        } else {
            fail++;
            println("FAIL " + name + " 往返不一致\n  第一次: " + show(p1)
                    + "\n  中间curl: " + show(c2) + "\n  第二次: " + show(p2));
        }
    }

    private static void checkReverseFails(String name, String packet, String expectInMessage) {
        try {
            String r = HttpToCurl.toCurl(packet, null);
            fail++;
            println("FAIL " + name + " — 本应抛 ParseException，却得到: " + show(r));
        } catch (ParseException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains(expectInMessage)) {
                pass++;
            } else {
                fail++;
                println("FAIL " + name + "\n  消息应含: " + expectInMessage + "\n  实际: " + msg);
            }
        }
    }

    private static String firstLine(String request) {
        int i = request.indexOf("\r\n");
        return i < 0 ? request : request.substring(0, i);
    }

    private static String headerValue(String request, String name) {        String[] lines = request.split("\r\n");
        for (String line : lines) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).equalsIgnoreCase(name)) {
                return line.substring(c + 1).trim();
            }
        }
        return null;
    }

    private static String bodyOf(String request) {
        int i = request.indexOf("\r\n\r\n");
        return i < 0 ? "" : request.substring(i + 4);
    }

    private static int bodyByteLen(String request) {
        return bodyOf(request).getBytes(UTF8).length;
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
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

    private static String show(String s) {
        if (s == null) {
            return "null";
        }
        return "\"" + s.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    private static void println(String s) {
        System.out.println(s);
    }

    private Tests() {
    }
}
