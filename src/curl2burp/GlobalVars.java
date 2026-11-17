package curl2burp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「全局变量」的存取：一行一个 NAME=VALUE，`#` 开头是注释。
 *
 * 目的是省掉在命令头上重复写 export / set，例如把
 * `BASE=https://service.example.com` 设一次，之后所有命令直接用 $BASE。
 * 命令文本里自己声明了同名变量时，仍以文本为准（scan 里会覆盖）。
 */
public final class GlobalVars {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private GlobalVars() {
    }

    /** 解析文本。非法行收进 warnings，不抛异常——界面上还要继续用。 */
    public static Map<String, String> parse(String text, List<String> warnings) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (text == null) {
            return out;
        }
        String[] lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                if (warnings != null) {
                    warnings.add("第 " + (i + 1) + " 行不是 名字=值，已忽略: " + line);
                }
                continue;
            }
            String name = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!isName(name)) {
                if (warnings != null) {
                    warnings.add("第 " + (i + 1) + " 行的变量名不合法，已忽略: " + name);
                }
                continue;
            }
            out.put(name, value);
        }
        return out;
    }

    /** 反向：键值对 → 编辑框里的文本。 */
    public static String format(Map<String, String> vars) {
        StringBuilder sb = new StringBuilder();
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
        }
        return sb.toString();
    }

    /** 默认存到 ~/.curl2burp/vars.txt；取不到用户目录就不落盘。 */
    public static File defaultFile() {
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) {
            return null;
        }
        return new File(new File(home, ".curl2burp"), "vars.txt");
    }

    public static Map<String, String> load() {
        File f = defaultFile();
        if (f == null || !f.isFile()) {
            return new LinkedHashMap<String, String>();
        }
        try {
            String text = new String(Files.readAllBytes(f.toPath()), UTF8);
            return parse(text, null);
        } catch (IOException e) {
            return new LinkedHashMap<String, String>();
        }
    }

    /** 保存失败返回错误信息，成功返回 null。 */
    public static String save(Map<String, String> vars) {
        File f = defaultFile();
        if (f == null) {
            return null;
        }
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                return "无法创建目录 " + dir;
            }
            List<String> lines = new ArrayList<String>();
            lines.add("# curl2burp 全局变量：一行一个 名字=值，# 开头是注释");
            if (vars != null) {
                for (Map.Entry<String, String> e : vars.entrySet()) {
                    lines.add(e.getKey() + "=" + e.getValue());
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String l : lines) {
                sb.append(l).append('\n');
            }
            Files.write(f.toPath(), sb.toString().getBytes(UTF8));
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    public static boolean isName(String s) {
        if (s == null || s.isEmpty() || !ShellVars.isNameStart(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!ShellVars.isNameChar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
