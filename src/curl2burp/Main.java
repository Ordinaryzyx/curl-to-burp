package curl2burp;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.undo.UndoManager;

/** Swing 入口与布局。转换逻辑全在 CurlToHttp，这里只管界面。 */
public final class Main {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 13);

    private final JFrame frame = new JFrame("curl2burp — curl 转 Burp 原始报文");
    private final JTextArea input = new JTextArea();
    private final JTextArea output = new JTextArea();
    /** 状态栏用可换行的文本域，长提示自动折行，不用再拖宽窗口。 */
    private final JTextArea status = new JTextArea(" ");
    private JScrollPane statusScroll;
    /** 状态栏最多占几行，再多就出竖向滚动条，避免挤掉正文。 */
    private static final int STATUS_MAX_ROWS = 6;
    private final JComboBox<Tokenizer.Dialect> dialectBox =
            new JComboBox<Tokenizer.Dialect>(Tokenizer.Dialect.values());
    private final JCheckBox crlfBox = new JCheckBox("CRLF", true);
    private final JCheckBox autoClBox = new JCheckBox("自动 Content-Length", true);
    private final JCheckBox expandBox = new JCheckBox("展开变量", true);
    private final JCheckBox httpsBox = new JCheckBox("报文转回时用 HTTPS", true);
    private final JButton varsButton = new JButton("全局变量");
    /** 界面上的全局变量，落盘在 ~/.curl2burp/vars.txt。 */
    private final Map<String, String> globalVars = new LinkedHashMap<String, String>(GlobalVars.load());
    private Color normalFg;
    private static final Color WARN_FG = new Color(0xC0, 0x50, 0x00);

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // 用默认外观也能跑
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new Main().show();
            }
        });
    }

    private Main() {
        input.setFont(MONO);
        output.setFont(MONO);
        input.setLineWrap(false);
        output.setLineWrap(false);
        input.setTabSize(4);
        output.setTabSize(4);

        JPanel left = buildPane("curl 命令", input, "复制 curl",
                "复制左侧这条 curl 命令的全部内容", "左侧的 curl 命令");
        JPanel right = buildPane("HTTP 请求报文", output, "复制报文",
                "复制右侧报文（含空行与 body 原文）", "右侧的报文");

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.5);
        split.setDividerLocation(0.5);

        JPanel north = new JPanel(new BorderLayout());
        north.add(buildToolbar(), BorderLayout.NORTH);
        north.add(buildOptions(), BorderLayout.SOUTH);

        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(false);
        status.setOpaque(false);
        status.setRows(1);
        status.setTabSize(4);
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        status.setToolTipText("");
        normalFg = status.getForeground();

        statusScroll = new JScrollPane(status);
        statusScroll.setBorder(null);
        statusScroll.setOpaque(false);
        statusScroll.getViewport().setOpaque(false);
        statusScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        statusScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

        frame.setLayout(new BorderLayout());
        frame.add(north, BorderLayout.NORTH);
        frame.add(split, BorderLayout.CENTER);
        frame.add(statusScroll, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1000, 620);
        frame.setMinimumSize(new Dimension(720, 480));
        frame.setLocationRelativeTo(null);

        // 窗口宽度一变，折行行数就变，状态栏高度跟着重算
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                // 等布局把新宽度算完再量，否则拿到的是旧宽度
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatusRows();
                    }
                });
            }
        });

        bindCtrlEnter(input, false);
        bindCtrlEnter(output, true);
        installUndo(input);
        installUndo(output);
        updateVarsButton();
    }

    private void show() {
        frame.setVisible(true);
    }

    /** 一栏 = 标题 + 复制按钮 + 文本域。复制按钮就贴在各自的框上，不用再猜复制的是哪个框。 */
    private JPanel buildPane(String title, final JTextArea area, String copyLabel,
            String copyTip, final String what) {
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));

        JButton copy = new JButton(copyLabel);
        Font bf = copy.getFont();
        copy.setFont(bf.deriveFont(bf.getSize2D() - 0.5f));
        copy.setMargin(new Insets(0, 8, 0, 8));
        copy.setFocusable(false);
        copy.setToolTipText(copyTip);
        copy.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                doCopy(area, what);
            }
        });

        JPanel head = new JPanel(new BorderLayout());
        head.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 4));
        head.add(label, BorderLayout.WEST);
        head.add(copy, BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(0x9A, 0x9A, 0x9A)));

        JPanel pane = new JPanel(new BorderLayout());
        pane.add(head, BorderLayout.NORTH);
        pane.add(scroll, BorderLayout.CENTER);
        return pane;
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        JButton convert = new JButton("转换");
        convert.setToolTipText("curl → 报文（Ctrl+Enter）");
        convert.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                doConvert();
            }
        });

        JButton toCurl = new JButton("转回 curl");
        toCurl.setToolTipText("报文 → curl（在右侧报文框里按 Ctrl+Enter）");
        toCurl.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                doReverse();
            }
        });

        JButton open = new JButton("打开");
        open.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                doOpen();
            }
        });

        JButton save = new JButton("保存");
        save.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                doSave();
            }
        });

        JButton clear = new JButton("清空");
        clear.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                input.setText("");
                output.setText("");
                setStatus(" ");
            }
        });

        bar.add(convert);
        bar.add(toCurl);
        bar.add(open);
        bar.add(save);
        bar.add(clear);
        bar.add(Box.createHorizontalStrut(12));
        varsButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                doEditVars();
            }
        });
        bar.add(varsButton);
        return bar;
    }

    private JPanel buildOptions() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.add(new JLabel("方言:"));
        dialectBox.setSelectedItem(Tokenizer.Dialect.AUTO);
        row.add(dialectBox);
        row.add(Box.createHorizontalStrut(8));
        row.add(crlfBox);
        row.add(autoClBox);
        expandBox.setToolTipText("命令前的 export A=b / A=b / set A=b 会被识别，并在 $A、${A}、%A% 处展开");
        row.add(expandBox);
        httpsBox.setToolTipText("原始报文的请求行里没有 http/https，只能按这个默认值生成");
        row.add(httpsBox);
        return row;
    }

    /** 在当前栏按 Ctrl+Enter，把它变成对面那栏。 */
    private void bindCtrlEnter(JTextArea area, final boolean reverse) {
        KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK);
        area.getInputMap(JComponent.WHEN_FOCUSED).put(ks, "curl2burp.go");
        area.getActionMap().put("curl2burp.go", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (reverse) {
                    doReverse();
                } else {
                    doConvert();
                }
            }
        });
    }

    /** JTextArea 默认没有撤销栈，这里补 Ctrl+Z / Ctrl+Y / Ctrl+Shift+Z。 */
    private static void installUndo(final JTextArea area) {
        final UndoManager manager = new UndoManager();
        area.getDocument().addUndoableEditListener(manager);
        area.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "curl2burp.undo");
        area.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "curl2burp.redo");
        area.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                        InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "curl2burp.redo");
        area.getActionMap().put("curl2burp.undo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (manager.canUndo()) {
                    manager.undo();
                }
            }
        });
        area.getActionMap().put("curl2burp.redo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (manager.canRedo()) {
                    manager.redo();
                }
            }
        });
    }

    private void doConvert() {
        Config cfg = new Config();
        Object d = dialectBox.getSelectedItem();
        cfg.dialect = d instanceof Tokenizer.Dialect ? (Tokenizer.Dialect) d : Tokenizer.Dialect.AUTO;
        cfg.crlf = crlfBox.isSelected();
        cfg.autoContentLength = autoClBox.isSelected();
        cfg.expandVariables = expandBox.isSelected();
        cfg.globalVars.putAll(globalVars);

        List<String> notes = new ArrayList<String>();
        try {
            String request = CurlToHttp.convert(input.getText(), cfg, notes);
            output.setText(request);
            output.setCaretPosition(0);
            setStatus(notes.isEmpty() ? "已生成报文" : join(notes, "；"));
        } catch (ParseException e) {
            setStatus("解析失败: " + e.getMessage());
        } catch (RuntimeException e) {
            setStatus("内部错误: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private void doReverse() {
        String text = output.getText();
        if (text == null || text.trim().isEmpty()) {
            setStatus("右侧没有报文：先粘一条原始 HTTP 报文，或先点「转换」");
            return;
        }
        List<String> notes = new ArrayList<String>();
        try {
            Config cfg = new Config();
            cfg.reverseHttps = httpsBox.isSelected();
            String curl = HttpToCurl.toCurl(text, cfg, notes);
            input.setText(curl);
            input.setCaretPosition(0);
            setStatus(notes.isEmpty() ? "已生成 curl 命令" : join(notes, "；"));
        } catch (ParseException e) {
            setStatus("解析失败: " + e.getMessage());
        } catch (RuntimeException e) {
            setStatus("内部错误: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private void doCopy(JTextArea area, String what) {
        String text = area.getText();
        if (text == null || text.isEmpty()) {
            setStatus(what + "是空的，没有可复制的内容");
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
            setStatus("已复制" + what + "，" + text.getBytes(UTF8).length + " 字节");
        } catch (RuntimeException e) {
            setStatus("复制失败: " + e.getMessage());
        }
    }

    private void doOpen() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = fc.getSelectedFile();
        try {
            input.setText(new String(Files.readAllBytes(f.toPath()), UTF8));
            setStatus("已打开 " + f.getName());
        } catch (IOException e) {
            setStatus("打开失败: " + e.getMessage());
        }
    }

    private void doSave() {
        String text = output.getText();
        if (text == null || text.isEmpty()) {
            setStatus("没有可保存的内容，先点「转换」");
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("request.txt"));
        if (fc.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = fc.getSelectedFile();
        try {
            Files.write(f.toPath(), text.getBytes(UTF8));
            setStatus("已保存 " + f.getName());
        } catch (IOException e) {
            setStatus("保存失败: " + e.getMessage());
            JOptionPane.showMessageDialog(frame, e.getMessage(), "保存失败",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private void setStatus(String text) {
        String t = text == null || text.isEmpty() ? " " : text;
        status.setText(t);
        status.setToolTipText(t);
        boolean warn = t.indexOf('\u26a0') >= 0
                || t.startsWith("解析失败") || t.startsWith("内部错误");
        status.setForeground(warn ? WARN_FG : normalFg);
        // 内容变了行数也可能变，重新算高度
        refreshStatusRows();
        status.setCaretPosition(0);
    }

    /** 按当前宽度重算状态栏高度，保证长提示整段可见（最多 STATUS_MAX_ROWS 行）。 */
    private void refreshStatusRows() {
        int avail = status.getWidth();
        if (avail <= 0) {
            avail = frame.getContentPane().getWidth();
        }
        if (avail <= 0) {
            avail = 984;
        }
        Insets ins = status.getInsets();
        avail -= (ins.left + ins.right);
        int rows = Math.max(1, Math.min(STATUS_MAX_ROWS, wrappedRows(status.getText(), avail)));
        if (status.getRows() != rows) {
            status.setRows(rows);
            // 让外层重新走一遍布局，状态栏高度立刻跟着变
            Container parent = statusScroll.getParent();
            if (parent != null) {
                parent.revalidate();
                parent.repaint();
            } else {
                statusScroll.revalidate();
            }
        }
    }

    /** 估算文本折行后占几行：逐字符累加宽度，超宽就换行。 */
    private int wrappedRows(String s, int availPx) {
        if (s == null || s.isEmpty() || availPx <= 0) {
            return 1;
        }
        FontMetrics fm = status.getFontMetrics(status.getFont());
        if (fm == null) {
            return 1;
        }
        int rows = 1;
        int used = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n') {
                rows++;
                used = 0;
                continue;
            }
            int w = fm.charWidth(cp);
            if (w <= 0) {
                w = fm.stringWidth(new String(Character.toChars(cp)));
            }
            if (w <= 0) {
                w = 6;
            }
            if (used > 0 && used + w > availPx) {
                rows++;
                used = w;
            } else {
                used += w;
            }
        }
        return rows;
    }

    // ------------------------------------------------------------ 全局变量

    private void updateVarsButton() {
        int n = globalVars.size();
        varsButton.setText(n == 0 ? "全局变量" : "全局变量 (" + n + ")");
        if (n == 0) {
            varsButton.setToolTipText("设置 BASE、TOKEN 这类反复用到的变量，命令里写 $BASE 即可");
        } else {
            StringBuilder names = new StringBuilder();
            for (String name : globalVars.keySet()) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(name);
            }
            varsButton.setToolTipText("已设置: " + names + "（命令里自己写了同名赋值时以命令为准）");
        }
    }

    private void doEditVars() {
        final JDialog dlg = new JDialog(frame, "全局变量", true);
        final JTextArea area = new JTextArea(GlobalVars.format(globalVars), 12, 52);
        area.setFont(MONO);
        area.setTabSize(4);
        installUndo(area);

        JLabel hint = new JLabel("<html>一行一个 <b>名字=值</b>，例如 <b>BASE=https://service.example.com</b>，"
                + "命令里写 $BASE、${BASE} 即可展开；<br>"
                + "命令前面自己写了 <b>export BASE=...</b> 时，以命令里的为准。"
                + "变量值只在本机保存，不会写进报文之外的任何地方。<br>"
                + "保存位置：" + (GlobalVars.defaultFile() == null
                        ? "（取不到用户目录，仅本次会话有效）"
                        : GlobalVars.defaultFile().getAbsolutePath()) + "</html>");

        JButton save = new JButton("保存");
        save.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                List<String> warnings = new ArrayList<String>();
                Map<String, String> parsed = GlobalVars.parse(area.getText(), warnings);
                if (!warnings.isEmpty()) {
                    StringBuilder sb = new StringBuilder("<html>");
                    for (String w : warnings) {
                        sb.append(w).append("<br>");
                    }
                    sb.append("<br>仍要保存其余 ").append(parsed.size()).append(" 个变量吗？</html>");
                    int r = JOptionPane.showConfirmDialog(dlg, sb.toString(), "有 "
                            + warnings.size() + " 行无法识别", JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE);
                    if (r != JOptionPane.YES_OPTION) {
                        return;
                    }
                }
                globalVars.clear();
                globalVars.putAll(parsed);
                String err = GlobalVars.save(globalVars);
                updateVarsButton();
                dlg.dispose();
                if (err == null) {
                    setStatus("全局变量已保存（" + globalVars.size() + " 个）");
                } else {
                    setStatus("\u26a0 全局变量本次会话内生效，但写入磁盘失败: " + err);
                }
            }
        });
        JButton cancel = new JButton("取消");
        cancel.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dlg.dispose();
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.add(save);
        buttons.add(cancel);

        JPanel top = new JPanel(new BorderLayout());
        top.add(hint, BorderLayout.NORTH);

        dlg.setLayout(new BorderLayout());
        dlg.add(top, BorderLayout.NORTH);
        dlg.add(new JScrollPane(area), BorderLayout.CENTER);
        dlg.add(buttons, BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);
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
}
