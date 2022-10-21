package io.simonis;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.Vector;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFormattedTextField;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.ToolTipManager;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;


class PidVirtual {
    private int pid;
    private long virtual;
    public PidVirtual(int pid, long virtual) {
        this.pid = pid;
        this.virtual = virtual;
    }
    public int getPid() {
        return pid;
    }
    public long getVirtual() {
        return virtual;
    }
}

class VirtualMapping {
    private long start; // inclusive
    private long end;   // excluseve
    private String info;
    public VirtualMapping(long start, long end, String info) {
        this.start = start;
        this.end = end;
        this.info = info;
    }
    public VirtualMapping(long start, long end) {
        this(start, end, null);
    }
}

class PhysicalMapping extends TreeMap<Long, ArrayList<PidVirtual>> {
    public void put(long physical, int pid, long virtual) {
        ArrayList<PidVirtual> list = this.get(physical);
        if (list == null) {
            list = new ArrayList<PidVirtual>();
            this.put(physical, list);
        }
        list .add(new PidVirtual(pid, virtual));
    }
}

// Bits to determine some of the uffd event attributes
final class UffdFlags {
    // 0 = read, 1 = write
    public static final int WRITE = 1;
    // 0 = zero page, 1 = load page
    public static final int LOAD = 2;
    // 0 = remove, 1 = pagefault
    public static final int PAGE = 4;
}

final class ReplayThreadState {
    public static final int STOP = 0;
    public static final int PLAY = 1;
    public static final int REWIND = 2;
    public static final int FORWARD = 3;
    public static final int RESET = 4;
}

class PhysicalMemory extends JPanel {
    private final long memory = UffdVisualizer.memory;
    private final int pageSize = UffdVisualizer.pageSize, width = UffdVisualizer.width, scale = UffdVisualizer.scale;
    private BufferedImage image;
    private PhysicalMapping physicalMapping;
    private HashMap<Integer, TreeMap<Long, Long>> v2pMappings;
    private HashMap<Integer, String> processMapping;
    private int pid;
    private static final Color shared = new Color(0x4D7A97);

    public PhysicalMemory(PhysicalMapping physicalMapping,
                          HashMap<Integer, TreeMap<Long, Long>> v2pMappings,
                          HashMap<Integer, String> processMapping, BufferedImage image) {
        super(new BorderLayout());
        this.physicalMapping = physicalMapping;
        this.v2pMappings = v2pMappings;
        this.processMapping = processMapping;
        this.image = image;
        this.setPreferredSize(new Dimension(image.getWidth(), image.getHeight()));
        ToolTipManager.sharedInstance().registerComponent(this);
        this.setCursor(new Cursor(Cursor.CROSSHAIR_CURSOR));
    }

    public void setImage(BufferedImage image) {
        this.image = image;
        repaint();
    }

    public String getToolTipText(MouseEvent event) {
        long address = (long)(((event.getY() + 1) / scale) * width + ((event.getX() + 1) / scale)) * pageSize;
        ArrayList<PidVirtual> pids = physicalMapping.get(address);
        StringBuilder sb = new StringBuilder("<html>");
        sb.append(String.format("%#018x<br/>", address));
        if (pids != null && pids.size() > 0) {
            for (PidVirtual pv : pids) {
                int pid = pv.getPid();
                sb.append(String.format("%d: %s (%#018x)<br/>", pid, processMapping.get(pid), pv.getVirtual()));
            }
        }
        return sb.append("</html>").toString();
    }


    public void paint(Graphics g) {
        super.paint(g);
        g.drawImage(image, 0, 0, null);
    }
}

class PhysicalViewPanel extends JPanel implements ListSelectionListener, ActionListener {
    private PhysicalMapping physicalMapping;
    private HashMap<Integer, TreeMap<Long, Long>> v2pMappings;
    private long uffdPhysical[];
    private byte uffdFlags[];
    private int uffdEntries, uffdIndex;
    private JList<String> processList;
    private JSplitPane horizontalSplitPane, verticalSplitPane;
    private PhysicalMemory physicalMemory;
    private JButton rewindButton, forwardButton, playButton;
    private JFormattedTextField uffdField;
    private ImageIcon playIcon, pauseIcon;
    private BufferedImage baseImage, pidImage, uffdImage;
    private Thread replayThread;
    private volatile int replayState = ReplayThreadState.STOP;
    private int pid;
    private static final Color shared = new Color(0x4D7A97);

    private BufferedImage createEmptyImage() {
        final long memory = UffdVisualizer.memory;
        final int pageSize = UffdVisualizer.pageSize, width = UffdVisualizer.width, scale = UffdVisualizer.scale;
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice gd = ge.getDefaultScreenDevice();
        GraphicsConfiguration gc = gd.getDefaultConfiguration();
        int xRes = width * scale;
        int yRes = (int)(memory / (pageSize * width)) * scale;
        BufferedImage emptyImage = gc.createCompatibleImage(xRes, yRes);
        return emptyImage;
    }

    private BufferedImage createBaseImage(PhysicalMapping physicalMapping) {
        final long memory = UffdVisualizer.memory;
        final int pageSize = UffdVisualizer.pageSize, width = UffdVisualizer.width, scale = UffdVisualizer.scale;
        BufferedImage baseImage = createEmptyImage();
        Graphics2D g2d = baseImage.createGraphics();
        g2d.setBackground(Color.LIGHT_GRAY);
        g2d.clearRect(0, 0, baseImage.getWidth(), baseImage.getHeight());
        g2d.setColor(Color.GRAY);
        for (long address : physicalMapping.keySet()) {
            int x = (((int)(address % (pageSize * width))) / pageSize) * scale;
            int y = (int)(address / (pageSize * width)) * scale;
            g2d.drawRect(x, y, scale - 1, scale - 1);
        }
        return baseImage;
    }

    private BufferedImage createPidImage(BufferedImage baseImage, PhysicalMapping physicalMapping,
                                         HashMap<Integer, TreeMap<Long, Long>> v2pMappings, int pid) {
        final long memory = UffdVisualizer.memory;
        final int pageSize = UffdVisualizer.pageSize, width = UffdVisualizer.width, scale = UffdVisualizer.scale;
        BufferedImage pidImage = createEmptyImage();
        Graphics2D g2d = pidImage.createGraphics();
        g2d.drawImage(baseImage, 0, 0, null);
        if (pid != 0) {
            for (long address : v2pMappings.get(pid).values()) {
                int x = (((int)(address % (pageSize * width))) / pageSize) * scale;
                int y = (int)(address / (pageSize * width)) * scale;
                if (physicalMapping.get(address) == null || physicalMapping.get(address).size() == 1) {
                    g2d.setColor(Color.DARK_GRAY);
                } else {
                    g2d.setColor(shared);
                }
                g2d.drawRect(x, y, scale - 1, scale - 1);
            }

        }
        return pidImage;
    }

    public PhysicalViewPanel(HashMap<Integer, String> processMapping, PhysicalMapping physicalMapping,
                             HashMap<Integer, TreeMap<Long, Long>> v2pMappings, long uffdPhysical[],
                             byte uffdFlags[], int uffdEntries, int uffdLoading, int uffdZeroing) {
        super(new BorderLayout());
        this.physicalMapping = physicalMapping;
        this.v2pMappings = v2pMappings;
        this.uffdPhysical = uffdPhysical;
        this.uffdFlags = uffdFlags;
        this.uffdEntries = uffdEntries;
        Vector<String> processes = new Vector<>();
        processMapping.forEach((pid, exe) -> {
            int slash = exe.lastIndexOf('/');
            processes.add(pid + ": " + (slash == -1 ? exe : exe.substring(slash + 1)));
        });
        processList = new JList(processes);
        processList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        processList.addListSelectionListener(this);
        JScrollPane processListScrollPane = new JScrollPane(processList);
        processList.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        baseImage = createBaseImage(physicalMapping);
        physicalMemory = new PhysicalMemory(physicalMapping, v2pMappings, processMapping, baseImage);
        JScrollPane pysicalMemoryScrollPane = new JScrollPane(physicalMemory,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        ImageIcon rewindIcon = new ImageIcon(getClass().getResource("/toolbarButtonGraphics/media/Rewind16.gif"));
        ImageIcon forwardIcon = new ImageIcon(getClass().getResource("/toolbarButtonGraphics/media/FastForward16.gif"));
        playIcon = new ImageIcon(getClass().getResource("/toolbarButtonGraphics/media/Play16.gif"));
        pauseIcon = new ImageIcon(getClass().getResource("/toolbarButtonGraphics/media/Pause16.gif"));
        rewindButton = new JButton(rewindIcon);
        rewindButton.addActionListener(this);
        forwardButton = new JButton(forwardIcon);
        forwardButton.addActionListener(this);
        playButton = new JButton(playIcon);
        playButton.addActionListener(this);
        playButton.setActionCommand("play");
        JLabel uffdLabel = new JLabel();
        uffdLabel.setText(
            String.format("<html><b>Userfaultfd:</b> %d events (%d pages / %dkb loaded,  %d pages / %dkb zeroed)</html>",
               uffdEntries, uffdLoading, uffdLoading * UffdVisualizer.pageSize, uffdZeroing, uffdZeroing * UffdVisualizer.pageSize));
        controlPanel.add(uffdLabel);
        uffdField = new JFormattedTextField();
        uffdField.setColumns(6);
        uffdField.setHorizontalAlignment(JTextField.RIGHT);
        uffdField.setEditable(false);
        uffdField.setValue(0);
        controlPanel.add(uffdField);
        controlPanel.add(rewindButton);
        controlPanel.add(playButton);
        controlPanel.add(forwardButton);


        this.add(pysicalMemoryScrollPane, BorderLayout.CENTER);
        this.add(processListScrollPane, BorderLayout.LINE_END);
        this.add(controlPanel, BorderLayout.PAGE_END);
    }

    public void valueChanged(ListSelectionEvent e) {
        JList<String> list = (JList<String>)e.getSource();
        String listElement = list.getSelectedValue();
        pid = Integer.parseInt(listElement.substring(0, listElement.indexOf(':')));
        pidImage = createPidImage(baseImage, physicalMapping, v2pMappings, pid);
        physicalMemory.setImage(pidImage);
        playButton.setActionCommand("play");
        playButton.setIcon(playIcon);
        replayThread = null;
        uffdIndex = 0;
        uffdField.setValue(uffdIndex);
    }

    public void actionPerformed(ActionEvent e) {
        final int pageSize = UffdVisualizer.pageSize, width = UffdVisualizer.width, scale = UffdVisualizer.scale;
        if (e.getSource().equals(playButton)) {
            if ("play".equals(playButton.getActionCommand())) {
                playButton.setActionCommand("pause");
                playButton.setIcon(pauseIcon);
                if (replayThread == null) {
                    uffdImage = createPidImage(baseImage, physicalMapping, v2pMappings, pid);
                    Graphics2D g2d = uffdImage.createGraphics();
                    uffdIndex = 0;
                    replayThread = new Thread() {
                        public void run() {
                            while (true) {
                                switch (replayState) {
                                    case ReplayThreadState.PLAY : {
                                        if (uffdIndex++ < uffdEntries) {
                                            long address = uffdPhysical[uffdIndex];
                                            int x = (((int)(address % (pageSize * width))) / pageSize) * scale;
                                            int y = (int)(address / (pageSize * width)) * scale;
                                            g2d.setColor(Color.GREEN);
                                            /*
                                            if (physicalMapping.get(address) == null || physicalMapping.get(address).size() == 1) {
                                                g2d.setColor(Color.DARK_GRAY);
                                            } else {
                                                g2d.setColor(shared);
                                            }
                                            */
                                            g2d.drawRect(x, y, scale - 1, scale - 1);
                                            uffdField.setValue(uffdIndex);
                                            physicalMemory.setImage(uffdImage);
                                            try {
                                                Thread.sleep(1);
                                            } catch (InterruptedException ie) {}
                                        } else {
                                            playButton.setActionCommand("play");
                                            playButton.setIcon(playIcon);
                                            replayState = ReplayThreadState.STOP;
                                            return;
                                        }
                                        break;
                                    }
                                    case ReplayThreadState.STOP : {
                                        try {
                                            Thread.sleep(1000);
                                        } catch (InterruptedException ie) {}
                                        break;
                                    }
                                }
                            }
                        }
                    };
                    replayThread.start();
                }
                replayState = ReplayThreadState.PLAY;
            } else {
                playButton.setActionCommand("play");
                playButton.setIcon(playIcon);
                replayState = ReplayThreadState.STOP;
            }
        } else if (e.getSource().equals(rewindButton)) {
            playButton.setActionCommand("play");
            playButton.setIcon(playIcon);
            replayState = ReplayThreadState.STOP;
            replayThread = null;
            uffdIndex = 0;
            uffdField.setValue(uffdIndex);
            physicalMemory.setImage(createPidImage(baseImage, physicalMapping, v2pMappings, pid));
        } else if (e.getSource().equals(forwardButton)) {

        }
    }
}

public class UffdVisualizer {
    public static final long memory = Long.getLong("uffdVisualizer.physicalMemory", 1024 * 1024 * 1024);
    public static final int pageSize = Integer.getInteger("uffdVisualizer.pageSize", 4096);
    public static final int width = Integer.getInteger("uffdVisualizer.width", 512);
    public static final int scale = Integer.getInteger("uffdVisualizer.scale", 2);

    // Global list of all mapped phiscal pages
    private PhysicalMapping physicalMapping;
    // Per process list of all virtual mappings
    private HashMap<Integer, Vector<VirtualMapping>> virtualMappings;
    // Per proecss list of all virtual to physical mappings
    private HashMap<Integer, TreeMap<Long, Long>> v2pMappings;
    // Pid to executable mapping
    private HashMap<Integer, String> processMapping;
    private long[] uffdPhysical;
    private byte[] uffdFlags;
    private int uffdEntries = 0;
    private int uffdLoading = 0;
    private int uffdZeroing = 0;
    private int pid;

    private void processMappingsLine(String line) {
        String fields[] = line.split(" ");
        if ("=".equals(fields[0])) {
            pid = Integer.parseInt(fields[1]);
            processMapping.put(pid, fields[2]);
            virtualMappings.put(pid, new Vector<VirtualMapping>());
            v2pMappings.put(pid, new TreeMap<Long, Long>());
        } else if ("v".equals(fields[0])) {
            virtualMappings.get(pid).add(
                new VirtualMapping(
                    Long.parseUnsignedLong(fields[1], 2, 18, 16),
                    Long.parseUnsignedLong(fields[2], 2, 18, 16),
                    (fields.length > 3) ? fields[3] : null));
        } else if ("p".equals(fields[0])) {
            long virtual = Long.parseUnsignedLong(fields[1], 2, 18, 16);
            long physical = Long.parseUnsignedLong(fields[2], 2, 18, 16);
            physicalMapping.put(physical, pid, virtual);
            v2pMappings.get(pid).put(virtual, physical);
        }
    }

    private void processUffdLine(String line) {
        // A line in the uffd log file looks as follows:
        // UFFD_EVENT_PAGEFAULT (r): 0x00007fffbbaa4000 0x00007fffbbaa4000  Loading: 0x0000000003cb5000 - 0x0000000003cb6000
        String fields[] = line.split(" ");
        if (fields.length < 6 || !fields[0].startsWith("UFFD_EVENT")) {
            return;
        }
        if ("UFFD_EVENT_PAGEFAULT".equals(fields[0])) {
            int entry = uffdEntries++;
            uffdPhysical[entry] = Long.parseUnsignedLong(fields[5], 2, 18, 16);
            uffdFlags[entry] |= UffdFlags.PAGE;
            if ("(w):".equals(fields[1])) {
                uffdFlags[entry] |= UffdFlags.WRITE;
            }
            if ("Loading:".equals(fields[4])) {
                uffdFlags[entry] |= UffdFlags.LOAD;
                uffdLoading++;
            } else {
                uffdZeroing++;
            }
        }
    }

    public UffdVisualizer(File mappings, File uffd) {
        long start = System.currentTimeMillis();
        physicalMapping = new PhysicalMapping();
        virtualMappings = new HashMap<Integer, Vector<VirtualMapping>>();
        v2pMappings = new HashMap<Integer, TreeMap<Long, Long>>();
        processMapping = new HashMap<Integer, String>();
        try {
            Files.lines(mappings.toPath()).forEach(l -> processMappingsLine(l));
            long parsedMappings = System.currentTimeMillis();

            System.out.println(String.format("Parsed %d mappings for %d processes in %dms.",
                physicalMapping.size(), processMapping.size(), parsedMappings - start));

            long uffdLength = Files.size(uffd.toPath());
            BufferedReader br = Files.newBufferedReader(uffd.toPath());
            do {
                String line = br.readLine();
                String[] tokens = line.split(" ");
                if (tokens.length > 0 && "UFFD_EVENT_PAGEFAULT".equals(tokens[0])) {
                    // Estimation of uffd events in the uffd file
                    uffdLength = (uffdLength / tokens[0].length()) + 10;
                    break;
                }
            } while (true);
            uffdPhysical = new long[(int)uffdLength];
            uffdFlags = new byte[(int)uffdLength];
            Files.lines(uffd.toPath()).forEach(l -> processUffdLine(l));

            System.out.println(String.format("Parsed %d UFFD events (%d pages / %dkb loaded,  %d pages / %dkb zeroed) in %dms.",
                uffdEntries, uffdLoading, uffdLoading * pageSize, uffdZeroing, uffdZeroing * pageSize,
                System.currentTimeMillis() - parsedMappings));
        } catch (IOException ioe) {
            System.err.println(ioe);
            System.exit(-1);
        }
    }

    public void createFrame() {
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Physical View", new PhysicalViewPanel(processMapping, physicalMapping, v2pMappings,
            uffdPhysical, uffdFlags, uffdEntries, uffdLoading, uffdZeroing));
        JFrame frame = new JFrame();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().add(tabbedPane);
        frame.pack();
        frame.setVisible(true);
    }

    private static void help() {
        System.out.println("\nUffdVisualizer <mapings-file> <uffd-file>\n");
        System.exit(-1);
    }
    public static void main(String args[]) {
        if (args.length != 2) {
            help();
        }
        File mappings = new File(args[0]);
        if (!mappings.canRead()) {
            System.err.println("Can't read " + mappings);
        }
        File uffd = new File(args[1]);
        if (!uffd.canRead()) {
            System.err.println("Can't read " + uffd);
        }
        UffdVisualizer uffdVisualizer = new UffdVisualizer(mappings, uffd);
        uffdVisualizer.createFrame();
    }
}