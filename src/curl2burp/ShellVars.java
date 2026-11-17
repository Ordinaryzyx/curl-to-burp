package curl2burp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 命令前的变量声明行：`export A=b` / `A=b` / `set A=b` / `$env:A='b'`。
 *
 * 这些行是 shell 的语句，不是 curl 的参数。以前它们会被当成参数塞进 Options，
 * 于是 `https_proxy=http://1.2.3.4:8080` 里那个 `://` 让工具把它误认成 URL，
 * 生成出 `GET //1.2.3.4:8080` 这种报文。这里把声明行切出来单独收成一张表，
 * 剩下的文本才是真正的 curl 命令。
 *
 * 只读文本、不读真实环境变量、不执行命令替换：同样的输入永远得到同样的报文。
 */
public final class ShellVars {

    private final Map<String, String> vars = new LinkedHashMap<String, String>();
    private final Set<String> used = new LinkedHashSet<String>();
    private final Set<String> missing = new LinkedHashSet<String>();
    private final Set<String> unsupported = new LinkedHashSet<String>();

    private String command = "";
    private int declared = 0;
    /** 声明行用了 set/setx → 文本是 cmd 风格，%NAME% 也应当展开。 */
    private boolean cmdStyle = false;

    private ShellVars() {
    }

    /** 剥掉命令前的赋值行；剩下的命令文本从 {@link #command()} 取。 */
    public static ShellVars scan(String raw, Tokenizer.Dialect dialect) {
        ShellVars sv = new ShellVars();
        if (raw == null) {
            return sv;
        }
        String text = raw.replace("\r\n", "\n").replace("\r", "");
        String[] lines = text.split("\n", -1);
        StringBuilder cmd = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.trim().isEmpty()) {
                i++;
                continue;
            }
            int pos = 0;
            int count = 0;
            int next;
            while ((next = consumeOne(line, pos, sv)) >= 0) {
                pos = next;
                count++;
            }
            if (count == 0) {
                break;                       // 不是赋值行 → 命令从这里开始
            }
            String rest = line.substring(pos).trim();
            if (rest.isEmpty()) {
                i++;                         // 整行都是赋值 → 丢掉
                continue;
            }
            // `A=b curl ...` 这种把赋值写在命令前面的写法
            cmd.append(rest);
            i++;
            break;
        }
        for (int k = i; k < lines.length; k++) {
            if (cmd.length() > 0) {
                cmd.append('\n');
            }
            cmd.append(lines[k]);
        }
        sv.command = cmd.toString();
        return sv;
    }

    /** 剥掉赋值行之后的命令文本。 */
    public String command() {
        return command;
    }

    /** 取变量值；未声明返回 null。 */
    public String value(String name) {
        String v = vars.get(name);
        if (v != null) {
            used.add(name);
        }
        return v;
    }

    /** 声明行写的是 set/setx（cmd 风格），此时 %NAME% 也该展开。 */
    public boolean isCmdStyle() {
        return cmdStyle;
    }

    void missing(String name) {
        missing.add(name);
    }

    void unsupported(String what) {
        unsupported.add(what);
    }

    /** 状态栏提示。声明数、用到的变量名、未赋值的变量名，不含变量值。 */
    public List<String> notes() {
        List<String> out = new ArrayList<String>();
        if (declared > 0) {
            out.add("剔除了 " + declared + " 个变量赋值（它们不是 curl 的参数）");
        }
        if (!used.isEmpty()) {
            out.add("已展开变量: " + join(used, ", "));
        }
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int k = 0;
            for (String name : missing) {
                if (k++ > 0) {
                    sb.append(", ");
                }
                sb.append('$').append(name);
            }
            out.add("变量没有赋值，已按字面保留: " + sb
                    + "（要展开就在命令前加一行 " + missing.iterator().next() + "=值）");
        }
        if (!unsupported.isEmpty()) {
            out.add("不执行命令替换 " + join(unsupported, ", ") + "，已按字面保留");
        }
        return out;
    }

    // ------------------------------------------------------------ 赋值行识别

    /**
     * 从 s 的 at 位置吃一个赋值。返回下一个位置；不是赋值返回 -1。
     * 四种写法都认，不按方言限制（`set A=1` 出现在 bash 里也没别的解释）。
     */
    private static int consumeOne(String s, int at, ShellVars sv) {
        int n = s.length();
        int p = at;
        while (p < n && isSpace(s.charAt(p))) {
            p++;
        }
        if (p >= n) {
            return -1;
        }

        boolean keyword = false;
        boolean cmdKeyword = false;
        if (wordAt(s, p, "export")) {
            p += 6;
            if (p < n && !isSpace(s.charAt(p))) {
                return -1;
            }
            keyword = true;
        } else if (wordAt(s, p, "setx")) {
            p += 4;
            if (p < n && !isSpace(s.charAt(p))) {
                return -1;
            }
            keyword = true;
            cmdKeyword = true;
        } else if (wordAt(s, p, "set")) {
            p += 3;
            if (p < n && !isSpace(s.charAt(p))) {
                return -1;
            }
            keyword = true;
            cmdKeyword = true;
        }
        while (p < n && isSpace(s.charAt(p))) {
            p++;
        }
        if (p >= n) {
            return -1;
        }

        // cmd 的 set "NAME=VALUE"
        if (keyword && s.charAt(p) == '"') {
            int close = s.indexOf('"', p + 1);
            if (close < 0) {
                return -1;
            }
            String inner = s.substring(p + 1, close);
            int eq = inner.indexOf('=');
            if (eq <= 0) {
                return -1;
            }
            String name = inner.substring(0, eq).trim();
            if (!isName(name)) {
                return -1;
            }
            sv.put(name, inner.substring(eq + 1), true);
            return close + 1;
        }

        // PowerShell 的 $env:NAME / $NAME
        if (s.charAt(p) == '$') {
            p++;
            if (s.regionMatches(p, "env:", 0, 4)) {
                p += 4;
            }
        }
        if (p >= n || !isNameStart(s.charAt(p))) {
            return -1;
        }

        int nameStart = p;
        p++;
        while (p < n && isNameChar(s.charAt(p))) {
            p++;
        }
        String name = s.substring(nameStart, p);

        // 分隔符：`=`，或者（有关键字时）空白（setx NAME VALUE）
        int q = p;
        while (q < n && isSpace(s.charAt(q))) {
            q++;
        }
        boolean hadSpace = q > p;
        if (q < n && s.charAt(q) == '=') {
            q++;
            while (q < n && isSpace(s.charAt(q))) {
                q++;
            }
        } else if (!keyword || !hadSpace) {
            return -1;
        }
        if (q >= n) {
            return -1;
        }

        StringBuilder val = new StringBuilder();
        int end = readValue(s, q, val);
        if (end < 0) {
            return -1;
        }
        sv.put(name, val.toString(), cmdKeyword);
        return end;
    }

    /** 读值：支持 '...' / "..." / 裸值。返回结束位置，失败返回 -1。 */
    private static int readValue(String s, int p, StringBuilder out) {
        int n = s.length();
        char c = s.charAt(p);
        if (c == '\'' || c == '"') {
            p++;
            while (p < n) {
                char ch = s.charAt(p);
                if (c == '"' && ch == '\\' && p + 1 < n) {
                    char e = s.charAt(p + 1);
                    if (e == '"' || e == '\\' || e == '$') {
                        out.append(e);
                        p += 2;
                        continue;
                    }
                }
                if (ch == c) {
                    return p + 1;
                }
                out.append(ch);
                p++;
            }
            return -1;                       // 引号没闭合，不当作赋值
        }
        while (p < n && !isSpace(s.charAt(p))) {
            out.append(s.charAt(p));
            p++;
        }
        return p;
    }

    private void put(String name, String raw, boolean cmdKeyword) {
        String v = raw == null ? "" : raw;
        if (v.indexOf("$(") >= 0 || v.indexOf('`') >= 0) {
            unsupported.add("$(...)");
        }
        if (cmdKeyword) {
            cmdStyle = true;
        }
        vars.put(name, expandKnown(v));
        declared++;
    }

    /** 值里引用已经声明过的变量（API=$BASE/api）就地展开；未声明的原样保留。 */
    private String expandKnown(String v) {
        if (v.indexOf('$') < 0) {
            return v;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = v.length();
        while (i < n) {
            char c = v.charAt(i);
            if (c != '$' || i + 1 >= n) {
                out.append(c);
                i++;
                continue;
            }
            int p = i + 1;
            boolean brace = v.charAt(p) == '{';
            if (brace) {
                p++;
            }
            if (p >= n || !isNameStart(v.charAt(p))) {
                out.append(c);
                i++;
                continue;
            }
            int start = p;
            while (p < n && isNameChar(v.charAt(p))) {
                p++;
            }
            String name = v.substring(start, p);
            String val = vars.get(name);
            if (brace) {
                if (p < n && v.charAt(p) == '}') {
                    p++;
                } else {
                    out.append(c);
                    i++;
                    continue;
                }
            }
            if (val == null) {
                out.append(v, i, p);
            } else {
                out.append(val);
            }
            i = p;
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- 小工具

    private static boolean wordAt(String s, int at, String word) {
        if (!s.regionMatches(true, at, word, 0, word.length())) {
            return false;
        }
        return true;
    }

    private static boolean isName(String s) {
        if (s.isEmpty() || !isNameStart(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!isNameChar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static boolean isNameStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
    }

    static boolean isNameChar(char c) {
        return isNameStart(c) || (c >= '0' && c <= '9');
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t';
    }

    private static String join(Iterable<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(s);
        }
        return sb.toString();
    }
}
