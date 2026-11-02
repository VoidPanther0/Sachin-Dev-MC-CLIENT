// java, doomsday/attach/InjectorGui.java, JDK 21, Swing, target: desktop launch of doomsday.jar (no CLI args)
package doomsday.attach;

import com.sun.tools.attach.VirtualMachineDescriptor;
import doomsday.util.Log;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * doomsday injector, "doomsdayclient" look: frameless dark window, light title bar with its own
 * min/max/close, a tab strip (only Inject is wired up — the rest are placeholder panes), a
 * background scanner that auto-detects a running Minecraft JVM, and one big Inject button.
 * All attach activity still lands in ~/.doomsday/injector-gui.log; agent-side output lands in
 * ~/.doomsday/agent.log once injected (see DoomsdayAgent).
 */
final class InjectorGui {

    private static final Color BG = new Color(0x1c, 0x1d, 0x1f);
    private static final Color TAB_BAR_BG = new Color(0x17, 0x18, 0x19);
    private static final Color TITLE_BAR_BG = new Color(0xf3, 0xee, 0xf7);
    private static final Color ACCENT = new Color(0x3a, 0x6f, 0xf0);
    private static final Color TEXT = new Color(0xea, 0xea, 0xea);
    private static final Color SUBTEXT = new Color(0x9a, 0x9a, 0x9e);
    private static final Color BTN_READY = new Color(0x3a, 0x6f, 0xf0);
    private static final Color BTN_IDLE = new Color(0x4a, 0x53, 0x6e);

    private InjectorGui() {}

    static void launch() {
        SwingUtilities.invokeLater(InjectorGui::build);
    }

    private static void build() {
        Path logPath = Log.redirectToFile("injector-gui.log");

        JFrame frame = new JFrame("doomsday injector");
        frame.setUndecorated(true);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(903, 713);
        frame.setMinimumSize(new Dimension(640, 480));
        frame.setLocationRelativeTo(null);
        frame.getContentPane().setBackground(BG);
        frame.setLayout(new BorderLayout());

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);

        root.add(buildTitleBar(frame), BorderLayout.NORTH);

        JPanel below = new JPanel(new BorderLayout());
        below.setBackground(BG);

        CardLayout cards = new CardLayout();
        JPanel cardPanel = new JPanel(cards);
        cardPanel.setBackground(BG);

        JLabel statusLabel = new JLabel("scanning...", SwingConstants.CENTER);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 15f));
        statusLabel.setForeground(TEXT);

        RoundedButton injectBtn = new RoundedButton("Inject");
        injectBtn.setEnabled(false);

        cardPanel.add(buildInjectPane(statusLabel, injectBtn), "inject");
        cardPanel.add(buildPlaceholderPane("Advanced inject", "per-build offset/mapping overrides — not wired up yet"), "advanced");
        cardPanel.add(buildPlaceholderPane("Install", "no installer flow — run this jar directly"), "install");
        cardPanel.add(buildPlaceholderPane("Updates", "you're on the build you compiled"), "updates");
        cardPanel.add(buildPlaceholderPane("Settings", "nothing to configure yet"), "settings");

        below.add(buildTabBar(cards, cardPanel), BorderLayout.NORTH);
        below.add(cardPanel, BorderLayout.CENTER);
        below.add(buildFooter(), BorderLayout.SOUTH);

        root.add(below, BorderLayout.CENTER);
        frame.add(root, BorderLayout.CENTER);

        wireAutoDetectAndInject(frame, statusLabel, injectBtn, logPath);

        frame.setVisible(true);
    }

    // ---- title bar (frameless window chrome) --------------------------------------------------

    private static JPanel buildTitleBar(JFrame frame) {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(TITLE_BAR_BG);
        bar.setPreferredSize(new Dimension(100, 36));

        JPanel icon = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0x2b, 0x2b, 0x2d));
                g2.fillOval(8, 7, 20, 20);
                g2.setColor(TITLE_BAR_BG);
                g2.fillOval(14, 13, 8, 8);
                g2.dispose();
            }
        };
        icon.setOpaque(false);
        icon.setPreferredSize(new Dimension(36, 36));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        controls.setOpaque(false);
        controls.add(titleBarButton("─", () -> frame.setState(Frame.ICONIFIED)));
        controls.add(titleBarButton("□", () -> frame.setExtendedState(
                frame.getExtendedState() == Frame.MAXIMIZED_BOTH ? Frame.NORMAL : Frame.MAXIMIZED_BOTH)));
        controls.add(titleBarButton("✕", () -> System.exit(0)));

        bar.add(icon, BorderLayout.WEST);
        bar.add(controls, BorderLayout.EAST);

        DragMove drag = new DragMove(frame);
        bar.addMouseListener(drag);
        bar.addMouseMotionListener(drag);
        return bar;
    }

    private static JButton titleBarButton(String symbol, Runnable action) {
        JButton b = new JButton(symbol);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setForeground(new Color(0x40, 0x40, 0x40));
        b.setFont(b.getFont().deriveFont(13f));
        b.setPreferredSize(new Dimension(44, 36));
        b.addActionListener(e -> action.run());
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { b.setContentAreaFilled(true); b.setBackground(new Color(0xe3, 0xda, 0xe8)); }
            @Override public void mouseExited(MouseEvent e) { b.setContentAreaFilled(false); }
        });
        return b;
    }

    private static final class DragMove extends MouseAdapter {
        private final JFrame frame;
        private Point start;
        DragMove(JFrame frame) { this.frame = frame; }
        @Override public void mousePressed(MouseEvent e) { start = e.getPoint(); }
        @Override public void mouseDragged(MouseEvent e) {
            Point loc = frame.getLocation();
            frame.setLocation(loc.x + e.getX() - start.x, loc.y + e.getY() - start.y);
        }
    }

    // ---- tab strip ------------------------------------------------------------------------------

    private static JPanel buildTabBar(CardLayout cards, JPanel cardPanel) {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(TAB_BAR_BG);
        bar.setBorder(BorderFactory.createEmptyBorder(0, 24, 0, 16));

        String[][] tabs = {{"Inject", "inject"}, {"Advanced inject", "advanced"}, {"Install", "install"}, {"Updates", "updates"}};
        TabStrip strip = new TabStrip(cards, cardPanel);
        for (String[] t : tabs) strip.addTab(t[0], t[1]);

        JButton gear = gearButton(cards, cardPanel, strip);

        bar.add(strip.panel(), BorderLayout.CENTER);
        bar.add(gear, BorderLayout.EAST);
        return bar;
    }

    private static JButton gearButton(CardLayout cards, JPanel cardPanel, TabStrip strip) {
        JButton gear = new JButton("⚙");
        gear.setToolTipText("settings");
        gear.setForeground(TEXT);
        gear.setFont(gear.getFont().deriveFont(16f));
        gear.setBorderPainted(false);
        gear.setContentAreaFilled(false);
        gear.setFocusPainted(false);
        gear.addActionListener(e -> {
            strip.selectNone();
            cards.show(cardPanel, "settings");
        });
        return gear;
    }

    /** Owns the tab JLabels and flips both their underline + the CardLayout content on click. */
    private static final class TabStrip {
        private final CardLayout cards;
        private final JPanel cardPanel;
        private final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 28, 0));
        private final java.util.List<TabLabel> tabLabels = new java.util.ArrayList<>();

        TabStrip(CardLayout cards, JPanel cardPanel) {
            this.cards = cards;
            this.cardPanel = cardPanel;
            panel.setOpaque(false);
        }

        void addTab(String text, String cardName) {
            TabLabel label = new TabLabel(text);
            label.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    select(label);
                    cards.show(cardPanel, cardName);
                }
            });
            tabLabels.add(label);
            panel.add(label);
            if (tabLabels.size() == 1) select(label);
        }

        private void select(TabLabel active) {
            for (TabLabel l : tabLabels) l.setSelected(l == active);
        }

        void selectNone() {
            for (TabLabel l : tabLabels) l.setSelected(false);
        }

        JPanel panel() { return panel; }
    }

    private static final class TabLabel extends JLabel {
        private boolean selected;
        TabLabel(String text) {
            super(text);
            setForeground(TEXT);
            setFont(getFont().deriveFont(15f));
            setBorder(BorderFactory.createEmptyBorder(16, 0, 10, 0));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        void setSelected(boolean s) { selected = s; setForeground(s ? Color.WHITE : SUBTEXT); repaint(); }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (selected) {
                g.setColor(ACCENT);
                g.fillRect(0, getHeight() - 2, getWidth(), 2);
            }
        }
    }

    // ---- inject pane ------------------------------------------------------------------------------

    private static JPanel buildInjectPane(JLabel statusLabel, RoundedButton injectBtn) {
        JPanel pane = new JPanel();
        pane.setBackground(BG);
        pane.setLayout(new GridBagLayout());

        JPanel inner = new JPanel();
        inner.setOpaque(false);
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        inner.add(statusLabel);
        inner.add(Box.createVerticalStrut(140));

        injectBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        injectBtn.setPreferredSize(new Dimension(300, 70));
        injectBtn.setMaximumSize(new Dimension(300, 70));
        inner.add(injectBtn);
        inner.add(Box.createVerticalStrut(28));

        JLabel link = new JLabel("Official site: doomsdayclient.com");
        link.setAlignmentX(Component.CENTER_ALIGNMENT);
        link.setForeground(SUBTEXT);
        inner.add(link);

        GridBagConstraints gc = new GridBagConstraints();
        pane.add(inner, gc);
        return pane;
    }

    private static JPanel buildPlaceholderPane(String title, String sub) {
        JPanel pane = new JPanel(new GridBagLayout());
        pane.setBackground(BG);
        JPanel inner = new JPanel();
        inner.setOpaque(false);
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        JLabel t = new JLabel(title);
        t.setForeground(TEXT);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 16f));
        t.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel s = new JLabel(sub);
        s.setForeground(SUBTEXT);
        s.setAlignmentX(Component.CENTER_ALIGNMENT);
        inner.add(t);
        inner.add(Box.createVerticalStrut(8));
        inner.add(s);
        pane.add(inner);
        return pane;
    }

    private static JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(BG);
        footer.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 16));
        JLabel version = new JLabel("v1.0.0", SwingConstants.RIGHT);
        version.setForeground(SUBTEXT);
        version.setFont(version.getFont().deriveFont(Font.BOLD, 12f));
        footer.add(version, BorderLayout.EAST);
        return footer;
    }

    // ---- scanning + inject wiring -----------------------------------------------------------------

    private static void wireAutoDetectAndInject(JFrame frame, JLabel statusLabel, RoundedButton injectBtn, Path logPath) {
        AtomicReference<VirtualMachineDescriptor> found = new AtomicReference<>();
        AtomicBoolean busy = new AtomicBoolean(false);

        ScheduledExecutorService scanner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "doomsday-gui-scan");
            t.setDaemon(true);
            return t;
        });
        scanner.scheduleWithFixedDelay(() -> {
            if (busy.get()) return;
            VirtualMachineDescriptor vmd = Injector.findMinecraftJvm();
            found.set(vmd);
            SwingUtilities.invokeLater(() -> {
                if (busy.get()) return;
                if (vmd != null) {
                    statusLabel.setText("Minecraft process found — pid " + vmd.id());
                    injectBtn.setEnabled(true);
                    injectBtn.setReady(true);
                } else {
                    statusLabel.setText("Minecraft processes not found");
                    injectBtn.setEnabled(false);
                    injectBtn.setReady(false);
                }
            });
        }, 0, 1500, TimeUnit.MILLISECONDS);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { scanner.shutdownNow(); }
        });

        injectBtn.addActionListener(e -> {
            VirtualMachineDescriptor target = found.get();
            if (target == null || busy.get()) return;
            busy.set(true);
            injectBtn.setEnabled(false);
            statusLabel.setText("injecting into pid " + target.id() + "...");
            String agentJar = Injector.currentJarPath();

            new Thread(() -> {
                try {
                    Injector.doAttach(target, agentJar);
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("injected — pid " + target.id());
                        JOptionPane.showMessageDialog(frame,
                                "injected into pid " + target.id() + "\nagent log: " + Log.LOG_DIR.resolve("agent.log"),
                                "doomsday", JOptionPane.INFORMATION_MESSAGE);
                    });
                } catch (Throwable t) {
                    Log.line("GUI inject failed:");
                    t.printStackTrace();
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("inject failed — see log");
                        JOptionPane.showMessageDialog(frame,
                                "inject failed: " + t + "\nfull trace in " + logPath,
                                "doomsday", JOptionPane.ERROR_MESSAGE);
                    });
                } finally {
                    busy.set(false);
                }
            }, "doomsday-inject").start();
        });
    }

    // ---- flat rounded button matching the reference's Inject button -------------------------------

    private static final class RoundedButton extends JButton {
        private boolean ready = false;

        RoundedButton(String text) {
            super(text);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFont(getFont().deriveFont(Font.BOLD, 20f));
            setForeground(Color.WHITE);
        }

        void setReady(boolean ready) { this.ready = ready; repaint(); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = isEnabled() ? BTN_READY : BTN_IDLE;
            if (getModel().isPressed()) fill = fill.darker();
            else if (getModel().isRollover() && isEnabled()) fill = fill.brighter();
            g2.setColor(fill);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 14, 14));
            g2.dispose();
            setForeground(isEnabled() ? Color.WHITE : new Color(0xb9, 0xbd, 0xc9));
            super.paintComponent(g);
        }
    }
}
