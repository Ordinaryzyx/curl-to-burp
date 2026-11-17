package curl2burp;

import java.util.LinkedHashMap;
import java.util.Map;

/** 转换配置。GUI 的可选项与它一一对应，测试里直接构造。 */
public final class Config {

    public Tokenizer.Dialect dialect = Tokenizer.Dialect.AUTO;

    /** true = CRLF，false = LF。 */
    public boolean crlf = true;

    /** 是否自动计算并写入 Content-Length。 */
    public boolean autoContentLength = true;

    /**
     * 是否把命令里声明的变量（export A=b / A=b / set A=b / $env:A='b'）
     * 在 $A、${A}、%A% 出现处展开成值。
     */
    public boolean expandVariables = true;

    /**
     * 界面上「全局变量」按钮里设的 名字=值。
     * 命令文本里自己声明过同名变量时，以文本为准。
     */
    public final Map<String, String> globalVars = new LinkedHashMap<String, String>();

    /**
     * 报文转回 curl 时，Host 里没写端口的情况下用 https 还是 http。
     * 原始报文里没有 scheme，只能由用户定这个默认值。
     */
    public boolean reverseHttps = true;

    public static Config defaults() {
        return new Config();
    }
}
