package curl2burp;

/** 转换配置。GUI 的三个可选项与它一一对应，测试里直接构造。 */
public final class Config {

    public Tokenizer.Dialect dialect = Tokenizer.Dialect.AUTO;

    /** true = CRLF，false = LF。 */
    public boolean crlf = true;

    /** 是否自动计算并写入 Content-Length。 */
    public boolean autoContentLength = true;

    /**
     * 报文转回 curl 时，Host 里没写端口的情况下用 https 还是 http。
     * 原始报文里没有 scheme，只能由用户定这个默认值。
     */
    public boolean reverseHttps = true;

    public static Config defaults() {
        return new Config();
    }
}
