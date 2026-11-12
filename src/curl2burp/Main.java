package curl2burp;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
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
    private final JLabel status = new JLabel(" ");
    private final JComboBox<Tokenizer.Dialect> dialectBox =
            new JComboBox<Tokenizer.Dialect>(Tokenizer.Dialect.values());
    private final JCheckBox crlfBox = new JCheckBox("CRLF", true);
    private final JCheckBox autoClBox = new JCheckBox("自动 Content-Length", true);
    private final JCheckBox httpsBox = new JCheckBox("报文转回时用 HTTPS", true);

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

        JScrollPane left = new JScrollPane(input);
        left.setBorder(BorderFactory.createTitledBorder("curl 命令"));
        JScrollPane right = new JScrollPane(output);
        right.setBorder(BorderFactory.createTitledBorder("HTTP 请求报文"));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.5);
        split.setDividerLocation(0.5);

        JPanel north = new JPanel(new BorderLayout());
        north.add(buildToolbar(), BorderLayout.NORTH);
        north.add(buildOptions(), BorderLayout.SOUTH);

        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        status.setToolTipText("");

        frame.setLayout(new BorderLayout());
        frame.add(north, BorderLayout.NORTH);
        frame.add(split, BorderLayout.CENTER);
        frame.add(status, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1000, 620);
        frame.setMinimumSize(new Dimension(720, 480));
        frame.setLocationRelativeTo(null);

        bindCtrlEnter(input, false);
        bindCtrlEnter(output, true);
        installUndo(input);
        installUndo(output);
    }

    private void show() {
        frame.setVisible(true);
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

        JButton copy = new JButton("复制");
        copy.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                doCopy();
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
        bar.add(copy);
        bar.add(open);
        bar.add(save);
        bar.add(clear);
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

    private void doCopy() {
        String text = output.getText();
        if (text == null || text.isEmpty()) {
            setStatus("没有可复制的内容，先点「转换」");
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
            setStatus("已复制 " + text.getBytes(UTF8).length + " 字节");
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
