package curl2burp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 第二段的产物：一条 curl 对应的结构化请求。
 * 只描述「要发什么」，不关心 Content-Length / CRLF 这类报文细节（那是 Builder 的事）。
 */
public final class RequestSpec {

    /** 请求方法。为 null 时由 Options 按 body 来源推断后填好。 */
    public String method;

    /** 第一条 URL 的原始形态（可能缺 scheme、可能带 fragment）。Builder 负责解析。 */
    public String url;

    /** --request-target 的覆盖值。非空时直接当请求行里的 path 用。 */
    public String requestTarget;

    /** 用户给的头，保持 -H 里的顺序。每项 = {name, value}。 */
    public final List<String[]> headers = new ArrayList<String[]>();

    /** 被 -H "Name:" 显式删除的头名（小写）。自动补头时不会再补回来。 */
    public final Set<String> suppressed = new LinkedHashSet<String>();

    /** 文本 body。与 bodyBytes 二选一。 */
    public String bodyText;

    /** 二进制 body（-T / -d @file 走这条）。非 null 时优先于 bodyText。 */
    public byte[] bodyBytes;

    /** 是否存在 body（-d '' 也算 —— Content-Length 要是 0 而不是没有）。 */
    public boolean hasBody;

    /** 给状态栏看的信息。 */
    public final List<String> notes = new ArrayList<String>();

    public void note(String text) {
        if (text != null && !notes.contains(text)) {
            notes.add(text);
        }
    }

    public String header(String name) {
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase(name)) {
                return h[1];
            }
        }
        return null;
    }

    public boolean hasHeader(String name) {
        return header(name) != null;
    }

    public boolean isSuppressed(String name) {
        return suppressed.contains(name.toLowerCase());
    }
}
