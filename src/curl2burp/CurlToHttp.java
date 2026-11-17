package curl2burp;

import java.util.List;

/**
 * 唯一的对外 seam：curl 文本进，报文字符串出。
 * 不依赖 Swing，测试直接调这里。
 */
public final class CurlToHttp {

    private CurlToHttp() {
    }

    public static String convert(String curlText, Config cfg) throws ParseException {
        return convert(curlText, cfg, null);
    }

    /**
     * @param notesOut 非 null 时把状态栏要显示的信息塞进去
     */
    public static String convert(String curlText, Config cfg, List<String> notesOut)
            throws ParseException {
        Config c = cfg == null ? Config.defaults() : cfg;
        Tokenizer.Dialect dialect = Tokenizer.resolveDialect(curlText, c.dialect);
        // 命令前的赋值行先剥掉：它们是 shell 语句，不是 curl 参数。
        ShellVars vars = ShellVars.scan(curlText, dialect, c.globalVars);
        List<String> tokens = Tokenizer.tokenizeWith(
                vars.command(), dialect, c.expandVariables ? vars : null);
        if (tokens.isEmpty()) {
            throw new ParseException("没有可解析的命令");
        }
        RequestSpec spec = Options.parse(tokens);
        List<String> varNotes = vars.notes();
        for (int k = varNotes.size() - 1; k >= 0; k--) {
            spec.notes.add(0, varNotes.get(k));
        }
        spec.notes.add(0, "按 " + dialect + " 方言解析");
        String request = Builder.build(spec, c);
        if (notesOut != null) {
            notesOut.addAll(spec.notes);
        }
        return request;
    }
}
