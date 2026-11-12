package curl2burp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 第一段：把原始文本还原成 curl 实际会看到的参数数组。
 * 只处理引号、转义、续行、方言差异，不理解任何选项含义。
 */
public final class Tokenizer {

    /** 输入方言。AUTO 只在扫描前用来决定两个冲突点的取舍。 */
    public enum Dialect {
        AUTO("自动"),
        POSIX("POSIX / bash"),
        CMD("Windows cmd"),
        POWERSHELL("PowerShell");

        private final String label;

        Dialect(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** cmd 特征：`^"` 或 `^` 续行。 */
    private static final Pattern CMD_HINT = Pattern.compile("\\^\"|\\^[ \\t]*\\n");
    /** PowerShell 特征：反引号续行（Chrome 的 PowerShell 导出就是这么换行的）。 */
    private static final Pattern PS_HINT = Pattern.compile("`[ \\t]*\\n");

    private Tokenizer() {
    }

    /**
     * 判定实际按哪种方言解析。AUTO 时：出现 cmd 特征 → CMD；其余 → POSIX
     * （`$'...'` 在 POSIX 分支里已原生支持，所以不需要单独一种方言）。
     */
    /**
     * 判定实际按哪种方言解析。AUTO 时：出现反引号续行 → POWERSHELL；
     * 出现 cmd 特征 → CMD；其余 → POSIX（`$'...'` 在 POSIX 分支里已原生支持）。
     */
    public static Dialect resolveDialect(String raw, Dialect requested) {
        if (requested != null && requested != Dialect.AUTO) {
            return requested;
        }
        if (raw == null) {
            return Dialect.POSIX;
        }
        if (PS_HINT.matcher(raw).find()) {
            return Dialect.POWERSHELL;
        }
        if (CMD_HINT.matcher(raw).find()) {
            return Dialect.CMD;
        }
        return Dialect.POSIX;
    }

    public static List<String> tokenize(String raw, Dialect dialect) throws ParseException {
        Dialect d = resolveDialect(raw, dialect);
        return tokenizeWith(raw, d);
    }

    /** 按已确定的方言切词（不做 AUTO 判定）。 */
    public static List<String> tokenizeWith(String raw, Dialect d) throws ParseException {
        List<String> tokens = new ArrayList<String>();
        if (raw == null) {
            return tokens;
        }
        boolean cmd = d == Dialect.CMD;
        boolean ps = d == Dialect.POWERSHELL;
        // 双引号内反斜杠只对 POSIX 有意义；cmd 与 PowerShell 里它是字面反斜杠
        boolean backslashEscapes = d == Dialect.POSIX;
        // cmd 与 PowerShell 里 "" 等价于一个字面 "
        boolean collapseDoubleQuote = d == Dialect.CMD || d == Dialect.POWERSHELL;

        // 行尾 \r 一律剔除，避免 CRLF 文本混进参数。
        String s = raw.replace("\r\n", "\n").replace("\r", "");
        int n = s.length();
        int i = 0;

        while (true) {
            while (i < n && isSpace(s.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }

            StringBuilder out = new StringBuilder();
            int quoteStart = -1;
            char quote = 0;

            while (i < n) {
                char c = s.charAt(i);

                if (quote == 0) {
                    if (isSpace(c)) {
                        break;
                    }
                    if (c == '\'') {
                        if (quoteStart < 0) {
                            quoteStart = i;
                        }
                        quote = '\'';
                        i++;
                        continue;
                    }
                    if (c == '"') {
                        if (quoteStart < 0) {
                            quoteStart = i;
                        }
                        quote = '"';
                        i++;
                        continue;
                    }
                    if (c == '$' && i + 1 < n && s.charAt(i + 1) == '\'') {
                        if (quoteStart < 0) {
                            quoteStart = i;
                        }
                        quote = 'A';
                        i += 2;
                        continue;
                    }
                    // 续行：反斜杠 / 尖号 / 反引号 紧贴行尾
                    if (c == '\\' && i + 1 < n && s.charAt(i + 1) == '\n') {
                        i += 2;
                        continue;
                    }
                    if (c == '^' && i + 1 < n && s.charAt(i + 1) == '\n') {
                        i += 2;
                        continue;
                    }
                    if (c == '`' && i + 1 < n && s.charAt(i + 1) == '\n') {
                        i += 2;
                        continue;
                    }
                    // PowerShell 的转义符是反引号
                    if (ps && c == '`' && i + 1 < n) {
                        out.append(s.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    // cmd 的 ^ 是转义符：^X → 字面 X
                    // 但 ^" 在参数起始位置是 Chrome 的「引号定界符」，不能当成字面引号
                    if (cmd && c == '^' && i + 1 < n && s.charAt(i + 1) == '"') {
                        if (quoteStart < 0) {
                            quoteStart = i;
                        }
                        quote = '"';
                        i += 2;
                        continue;
                    }
                    if (cmd && c == '^' && i + 1 < n) {
                        out.append(s.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    // POSIX 引号外：\ 转义空白与 ' " \ $ ` 这几个字符
                    // （'a'\''b' 这种写法要靠这条还原成 a'b）
                    if (backslashEscapes && c == '\\' && i + 1 < n) {
                        char e = s.charAt(i + 1);
                        if (isSpace(e) || e == '\'' || e == '"' || e == '\\' || e == '$' || e == '`') {
                            out.append(e);
                            i += 2;
                            continue;
                        }
                    }
                    out.append(c);
                    i++;
                    continue;
                }

                if (quote == '\'') {
                    // 单引号：内部不解释任何转义
                    if (c == '\'') {
                        quote = 0;
                        i++;
                        continue;
                    }
                    out.append(c);
                    i++;
                    continue;
                }

                if (quote == 'A') {
                    // $'...'：ANSI-C 引号
                    if (c == '\'') {
                        quote = 0;
                        i++;
                        continue;
                    }
                    if (c == '\\' && i + 1 < n) {
                        int used = appendAnsiC(s, i, out);
                        if (used > 0) {
                            i += used;
                            continue;
                        }
                    }
                    out.append(c);
                    i++;
                    continue;
                }

                // quote == '"'
                if (cmd) {
                    // Chrome cmd 导出里内嵌引号的三种写法，统一还原成一个 "
                    if (i + 3 < n && s.charAt(i) == '^' && s.charAt(i + 1) == '\\'
                            && s.charAt(i + 2) == '^' && s.charAt(i + 3) == '"') {
                        out.append('"');
                        i += 4;
                        continue;
                    }
                    if (i + 2 < n && s.charAt(i) == '\\' && s.charAt(i + 1) == '^'
                            && s.charAt(i + 2) == '"') {
                        out.append('"');
                        i += 3;
                        continue;
                    }
                    if (i + 2 < n && s.charAt(i) == '^' && s.charAt(i + 1) == '\\'
                            && s.charAt(i + 2) == '"') {
                        out.append('"');
                        i += 3;
                        continue;
                    }
                    if (c == '^' && i + 1 < n && s.charAt(i + 1) == '"') {
                        // 后面紧跟空白或到结尾 → 收尾定界符；否则就是字面引号
                        if (i + 2 >= n || isSpace(s.charAt(i + 2))) {
                            quote = 0;
                            i += 2;
                        } else {
                            out.append('"');
                            i += 2;
                        }
                        continue;
                    }
                    if (c == '^' && i + 1 < n && s.charAt(i + 1) == '\n') {
                        i += 2;
                        continue;
                    }
                    if (c == '^' && i + 1 < n) {
                        out.append(s.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (c == '"') {
                        if (i + 1 < n && s.charAt(i + 1) == '"') {
                            out.append('"');
                            i += 2;
                            continue;
                        }
                        quote = 0;
                        i++;
                        continue;
                    }
                    out.append(c);
                    i++;
                    continue;
                }
                if (ps && c == '`' && i + 1 < n) {
                    // PowerShell 双引号内的反引号转义
                    char e = s.charAt(i + 1);
                    if (e == '\n') {
                        i += 2;
                        continue;
                    }
                    if (e == 'n') {
                        out.append('\n');
                    } else if (e == 't') {
                        out.append('\t');
                    } else if (e == 'r') {
                        out.append('\r');
                    } else if (e == '0') {
                        out.append('\0');
                    } else if (e == 'a') {
                        out.append('\u0007');
                    } else if (e == 'b') {
                        out.append('\b');
                    } else if (e == 'f') {
                        out.append('\f');
                    } else if (e == 'v') {
                        out.append('\u000B');
                    } else {
                        out.append(e);
                    }
                    i += 2;
                    continue;
                }
                if (backslashEscapes && c == '\\' && i + 1 < n) {
                    char e = s.charAt(i + 1);
                    if (e == '\n') {
                        // 双引号内的续行
                        i += 2;
                        continue;
                    }
                    // POSIX 双引号里只有 " \ $ ` 会被反斜杠转义
                    if (e == '"' || e == '\\' || e == '$' || e == '`') {
                        out.append(e);
                        i += 2;
                        continue;
                    }
                    // 其余原样保留：POSIX 里 "a\nb" 的 \n 就是两个字符，
                    // 换成控制字符会把 JSON body 和 Windows 路径的 \t 弄坏
                    out.append('\\');
                    i++;
                    continue;
                }
                if (c == '"') {
                    if (collapseDoubleQuote && i + 1 < n && s.charAt(i + 1) == '"') {
                        out.append('"');
                        i += 2;
                        continue;
                    }
                    quote = 0;
                    i++;
                    continue;
                }
                out.append(c);
                i++;
            }

            if (quote != 0) {
                String open = quote == 'A' ? "$'" : String.valueOf(quote);
                throw new ParseException(
                        "第 " + (tokens.size() + 1) + " 个参数的引号未闭合（从第 "
                                + (quoteStart + 1) + " 个字符开始的 " + open + "）",
                        quoteStart + 1);
            }
            // 只由续行/空白产生的空 token（例如 "-d \" 换行 后接参数"）要丢掉，
            // 否则会被当成上一个选项的值；真正的空参数来自 '' 或 ""，那时 quoteStart >= 0
            if (out.length() == 0 && quoteStart < 0) {
                continue;
            }
            tokens.add(out.toString());
        }
        return tokens;
    }

    /**
     * 处理 $'...' 里的一个反斜杠转义。返回消耗的字符数，0 表示无法识别（调用方按字面处理）。
     * s[at] 一定是反斜杠。
     */
    private static int appendAnsiC(String s, int at, StringBuilder out) {
        int n = s.length();
        char e = s.charAt(at + 1);
        switch (e) {
            case 'n':
                out.append('\n');
                return 2;
            case 'r':
                out.append('\r');
                return 2;
            case 't':
                out.append('\t');
                return 2;
            case '\\':
                out.append('\\');
                return 2;
            case '\'':
                out.append('\'');
                return 2;
            case '"':
                out.append('"');
                return 2;
            case 'a':
                out.append('\u0007');
                return 2;
            case 'b':
                out.append('\b');
                return 2;
            case 'f':
                out.append('\f');
                return 2;
            case 'v':
                out.append('\u000B');
                return 2;
            case '0':
                // \0 后面不再接八进制数字时就是 NUL
                if (at + 2 < n && s.charAt(at + 2) >= '0' && s.charAt(at + 2) <= '7') {
                    return 0;
                }
                out.append('\0');
                return 2;
            case 'x': {
                int p = at + 2;
                int val = 0;
                int digits = 0;
                while (p < n && digits < 2 && isHex(s.charAt(p))) {
                    val = val * 16 + hexVal(s.charAt(p));
                    p++;
                    digits++;
                }
                if (digits == 0) {
                    return 0;
                }
                out.append((char) val);
                return p - at;
            }
            default:
                break;
        }
        if (e >= '0' && e <= '7') {
            int p = at + 1;
            int val = 0;
            int digits = 0;
            while (p < n && digits < 3 && s.charAt(p) >= '0' && s.charAt(p) <= '7') {
                val = val * 8 + (s.charAt(p) - '0');
                p++;
                digits++;
            }
            out.append((char) (val & 0xFF));
            return p - at;
        }
        return 0;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        return c - 'A' + 10;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\u000B';
    }
}
