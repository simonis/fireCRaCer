package io.simonis;

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
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
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

class PhysicalMemory extends JPanel {

    private final long memory;
    private final int pageSize, width, scale;
    private BufferedImage baseImage;
    private PhysicalMapping physicalMapping;
    private HashMap<Integer, TreeMap<Long, Long>> v2pMappings;
    private HashMap<Integer, String> processMapping;
    private int pid;
    private static final Color shared = new Color(0x4D7A97);

    public PhysicalMemory(PhysicalMapping physicalMapping,
                          HashMap<Integer, TreeMap<Long, Long>> v2pMappings,
                          HashMap<Integer, String> processMapping, int pid) {
        super(new BorderLayout());
        this.physicalMapping = physicalMapping;
        this.v2pMappings = v2pMappings;
        this.processMapping = processMapping;
        this.pid = pid;
        this.memory = Long.getLong("uffdVisualizer.physicalMemory", 1024 * 1024 * 1024);
        this.pageSize = Integer.getInteger("uffdVisualizer.pageSize", 4096);
        this.width = Integer.getInteger("uffdVisualizer.width", 512);
        this.scale = Integer.getInteger("uffdVisualizer.scale", 2);
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice gd = ge.getDefaultScreenDevice();
        GraphicsConfiguration gc = gd.getDefaultConfiguration();
        int xRes = width * scale;
        int yRes = (int)(memory / (pageSize * width)) * scale;
        baseImage = gc.createCompatibleImage(xRes, yRes);
        Graphics2D g2d = baseImage.createGraphics();
        g2d.setBackground(Color.LIGHT_GRAY);
        g2d.clearRect(0, 0, xRes, yRes);
        this.setPreferredSize(new Dimension(xRes, yRes));
        g2d.setColor(Color.GRAY);
        for (long address : physicalMapping.keySet()) {
            int x = (((int)(address % (pageSize * width))) / pageSize) * scale;
            int y = (int)(address / (pageSize * width)) * scale;
            g2d.drawRect(x, y, scale - 1, scale - 1);
        }
        ToolTipManager.sharedInstance().registerComponent(this);
        this.setCursor(new Cursor(Cursor.CROSSHAIR_CURSOR));
    }

    public void setPid(int pid) {
        this.pid = pid;
        repaint();
    }

    public String getToolTipText(MouseEvent event) {
        long address = (long)(((event.getY() + 1) / scale) * width + ((event.getX() + 1) / scale)) * pageSize;
        ArrayList<PidVirtual> pids = physicalMapping.get(address);
        if (pids != null && pids.size() > 0) {
            StringBuilder sb = new StringBuilder("<html>");
            for (PidVirtual pv : pids) {
                int pid = pv.getPid();
                sb.append(String.format("%d: %s (%#018x)<br/>", pid, processMapping.get(pid), pv.getVirtual()));
            }
            return sb.append("</html>").toString();
        }
        return null;
    }


    public void paint(Graphics g) {
        super.paint(g);
        g.drawImage(baseImage, 0, 0, null);
        if (pid != 0) {
            for (long address : v2pMappings.get(pid).values()) {
                int x = (((int)(address % (pageSize * width))) / pageSize) * scale;
                int y = (int)(address / (pageSize * width)) * scale;
                if (physicalMapping.get(address) == null || physicalMapping.get(address).size() == 1) {
                    g.setColor(Color.DARK_GRAY);
                } else {
                    g.setColor(shared);
                }
                g.drawRect(x, y, scale - 1, scale - 1);
            }

        }
    }
}

class PhysicalViewPanel extends JPanel implements ListSelectionListener, ActionListener {
    private JList<String> processList;
    private JSplitPane horizontalSplitPane, verticalSplitPane;
    private PhysicalMemory physicalMemory;
    private JButton rewindButton, forwardButton, playButton;
    private ImageIcon playIcon, pauseIcon;

    public PhysicalViewPanel(HashMap<Integer, String> processMapping, PhysicalMapping physicalMapping, HashMap<Integer, TreeMap<Long, Long>> v2pMappings) {
        super(new BorderLayout());
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

        physicalMemory = new PhysicalMemory(physicalMapping, v2pMappings, processMapping, 0);
        JScrollPane pysicalMemoryScrollPane = new JScrollPane(physicalMemory,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);

        // TBD
        JPanel controlPanel = new JPanel();
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
        int pid = Integer.parseInt(listElement.substring(0, listElement.indexOf(':')));
        physicalMemory.setPid(pid);
    }

    public void actionPerformed(ActionEvent e) {
        if (e.getSource().equals(playButton)) {
            if ("play".equals(playButton.getActionCommand())) {
                playButton.setActionCommand("pause");
                playButton.setIcon(pauseIcon);
            } else {
                playButton.setActionCommand("play");
                playButton.setIcon(playIcon);
            }
        }
    }
}

public class UffdVisualizer {
    // Global list of all mapped phiscal pages
    private PhysicalMapping physicalMapping;
    // Per process list of all virtual mappings
    private HashMap<Integer, Vector<VirtualMapping>> virtualMappings;
    // Per proecss list of all virtual to physical mappings
    private HashMap<Integer, TreeMap<Long, Long>> v2pMappings;
    // Pid to executable mapping
    private HashMap<Integer, String> processMapping;
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
    public UffdVisualizer(File mappings, File uffd) {
        long start = System.currentTimeMillis();
        physicalMapping = new PhysicalMapping();
        virtualMappings = new HashMap<Integer, Vector<VirtualMapping>>();
        v2pMappings = new HashMap<Integer, TreeMap<Long, Long>>();
        processMapping = new HashMap<Integer, String>();
        try {
            Files.lines(mappings.toPath())
                .forEach(l -> processMappingsLine(l));
        } catch (IOException ioe) {
            System.err.println(ioe);
            System.exit(-1);
        }
        long parsedMappings = System.currentTimeMillis();
        System.out.println(String.format("Parsed %d mappings for %d processes in %dms.",
            physicalMapping.size(), processMapping.size(), parsedMappings - start));
    }

    public void createFrame() {
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Physical View", new PhysicalViewPanel(processMapping, physicalMapping, v2pMappings));
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