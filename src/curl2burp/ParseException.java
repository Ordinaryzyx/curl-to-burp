package curl2burp;

/**
 * 解析失败。message 面向用户，必须能直接看懂并据此改命令。
 * position 是原始文本里的字符偏移（1 起，0 表示未知）。
 */
public class ParseException extends Exception {

    private final int position;

    public ParseException(String message) {
        this(message, 0);
    }

    public ParseException(String message, int position) {
        super(message);
        this.position = position;
    }

    /** 原始文本中的字符位置（1 起），0 表示不适用。 */
    public int getPosition() {
        return position;
    }
}
