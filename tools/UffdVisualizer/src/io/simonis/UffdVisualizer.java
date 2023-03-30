package io.simonis;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFormattedTextField;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.ToolTipManager;
import javax.swing.event.TreeModelListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;


class Colors {
    public static Color BACKGROUND = Color.getColor("uffdVisualizer.backgroundColor", Color.LIGHT_GRAY);
    public static Color MEMORY = Color.getColor("uffdVisualizer.memoryColor", Color.GRAY);
    public static Color SELECTED = Color.getColor("uffdVisualizer.selectedColor", Color.DARK_GRAY);
    public static Color SHARED = Color.getColor("uffdVisualizer.sharedColor", 0x4D7A97);
    public static Color LOADED_SELECTED = Color.getColor("uffdVisualizer.loadedColor", Color.ORANGE);
    public static Color LOADED = Color.getColor("uffdVisualizer.loadedColor", Color.YELLOW);
    public static Color NEW = Color.getColor("uffdVisualizer.sharedColor", Color.BLUE);
}

record PidVirtual (
    int pid,
    long virtual) {
}

class VirtualMapping {
    private long start; // inclusive
    private long end;   // exclusive
    private long rss;
    private long reloaded;
    private String info;
    public VirtualMapping(long start, long end) {
        this(start, end, null);
    }
    public VirtualMapping(long start, long end, String info) {
        this.start = start;
        this.end = end;
        this.info = info;
    }
    public long start() {
        return start;
    }
    public long end() {
        return end;
    }
    public String info() {
        return info;
    }
    public long rss() {
        return rss;
    }
    public long reloaded() {
        return reloaded;
    }
    public long size() {
        return end - start;
    }
    public void setPhysicalState(TreeMap<Long, Long> v2pMappings, PhysicalMapping pm) {
        var mapped = v2pMappings.subMap(start, end);
        rss = mapped.size() * UffdVisualizer.pageSize;
        for (long physical : mapped.values()) {
            if (pm.isReloaded(physical)) {
                reloaded += UffdVisualizer.pageSize;
            }
        }
    }
    public boolean contains(long address) {
        return Long.compareUnsigned(start, address) <= 0 && Long.compareUnsigned(address, end) < 0;
    }
    @Override
    public String toString() {
        return String.format("%#018x-%#018x", start, end);
    }
}

class ReservedMapping extends VirtualMapping {
    private Vector<VirtualMapping> committedMappings;
    public ReservedMapping(long start, long end, String info) {
        super(start, end, info);
    }
    public void addCommittedMapping(VirtualMapping vm) {
        if (committedMappings == null) {
            committedMappings = new Vector<>();
        }
        committedMappings.add(vm);
    }
    public Vector<VirtualMapping> committedMappings() {
        return committedMappings;
    }
    @Override
    public String toString() {
        return info();
    }
}

// Maps a physical address to an Object array:
//   Object[0]: an ArrayList of PidVirtual (i.e. the pids and the virtual address
//              within that pid that map the corresponding physical address).
//   Object[1]: the UffdFlags of the corresponding physical address
class PhysicalMapping extends TreeMap<Long, Object[]> {
    public void put(long physical, int pid, long virtual) {
        Object objArr[] = this.get(physical);
        if (objArr == null) {
            objArr = new Object[2];
            this.put(physical, objArr);
        }
        ArrayList<PidVirtual> list = (ArrayList<PidVirtual>)objArr[0];
        if (list == null) {
            list = new ArrayList<PidVirtual>();
            objArr[0] = list;
        }
        list.add(new PidVirtual(pid, virtual));
    }
    public ArrayList<PidVirtual> getPidVirtual(long physical) {
        Object objArr[] = this.get(physical);
        if (objArr == null) {
            return null;
        }
        return (ArrayList<PidVirtual>)objArr[0];
    }
    public void put(long physical, byte uffdFlags) {
        Object objArr[] = this.get(physical);
        if (objArr == null) {
            objArr = new Object[2];
            this.put(physical, objArr);
        }
        objArr[1] = Byte.valueOf(uffdFlags);
    }
    public Byte getUffdFlags(long physical) {
        Object objArr[] = this.get(physical);
        if (objArr == null) {
            return null;
        }
        return (Byte)objArr[1];
    }
    public boolean isReloaded(long physical) {
        Byte uffdFlags = getUffdFlags(physical);
        if (uffdFlags == null) {
            return false;
        }
        return (uffdFlags & UffdFlags.SET) > 0;
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
    // All the previous flags are only valid if this flag is '1' (i.e. SET)
    // 0 = empty, 1 = set
    public static final int SET = 128;
}

class UffdState {
    long[] uffdPhysical;
    byte[] uffdFlags;
    int uffdEntries;
    int uffdLoading;
    int uffdZeroing;
    public UffdState(long[] uffdPhysical, byte[] uffdFlags) {
        this.uffdPhysical = uffdPhysical;
        this.uffdFlags = uffdFlags;
    }
}

class MemMapTreeModel implements TreeModel {
    private static final String ROOT = "ROOT";
    private HashMap<Integer, String> processMapping;
    private HashMap<Integer, Vector<VirtualMapping>> virtualMappings;
    private HashMap<Integer, Vector<VirtualMapping>> nmtMappings;
    private Vector<Process> processes = new Vector<>();

    static class Memory {
        protected long virt;
        protected long rss;
        protected long reloaded;
    }
    static class Process extends Memory {
        private String process;
        private int pid;
        private Object[] mappings;
        public Process(String process, int pid, int mappings) {
            this.process = process;
            this.pid = pid;
            this.mappings = new Object[mappings];
        }
        public int mappings() {
            return mappings.length;
        }
        public Object getMapping(int index) {
            if (mappings[index] == null) {
                mappings[index] = index == 0 ? new Pmap(this) : new NMT(this);
            }
            return mappings[index];
        }
        public int pid() {
            return pid;
        }
        @Override
        public String toString() {
            return process;
        }
    }

    static class Pmap extends Memory {
        private Process process;
        public Pmap(Process process) {
            this.process = process;
        }
        public Process process() {
            return process;
        }
        @Override
        public String toString() {
            return process.pid() <= 0 ? "physical" : "pmap";
        }
    }

    static class NMT extends Memory {
        private Process process;
        public NMT(Process process) {
            this.process = process;
        }
        public Process process() {
            return process;
        }
        @Override
        public String toString() {
            return "NMT";
        }
    }

    public MemMapTreeModel(HashMap<Integer, String> processMapping,
                           HashMap<Integer, Vector<VirtualMapping>> virtualMappings,
                           HashMap<Integer, Vector<VirtualMapping>> nmtMappings) {
        this.processMapping = processMapping;
        this.virtualMappings = virtualMappings;
        this.nmtMappings = nmtMappings;
        processes = new Vector<>();
        processMapping.forEach((pid, exe) -> {
            int slash = exe.lastIndexOf('/');
            processes.add(new Process(pid + ": " + (slash == -1 ? exe : exe.substring(slash + 1)),
                                      pid, nmtMappings.get(pid) == null ? 1 : 2));
        });
        processes.sort(Comparator.comparing(p -> Integer.valueOf(p.toString().substring(0, p.toString().indexOf(':')))));
    }

    @Override
    public Object getChild(Object parent, int index) {
        if (parent == ROOT) {
            return processes.get(index);
        } else if (parent instanceof Process) {
            return ((Process)parent).getMapping(index);
        } else if (parent instanceof Pmap) {
            return virtualMappings.get(((Pmap)parent).process().pid()).get(index);
        } else if (parent instanceof NMT) {
            return nmtMappings.get(((NMT)parent).process().pid()).get(index);
        } else if (parent instanceof ReservedMapping) {
            return ((ReservedMapping)parent).committedMappings().get(index);
        } else {
            return "ERROR";
        }

    }

    @Override
    public int getChildCount(Object parent) {
        if (parent == ROOT) {
            return processes.size();
        } else if (parent instanceof Process) {
            return ((Process)parent).mappings();
        } else if (parent instanceof Pmap) {
            return virtualMappings.get(((Pmap)parent).process().pid()).size();
        } else if (parent instanceof NMT) {
            return nmtMappings.get(((NMT)parent).process().pid()).size();
        } else if (parent instanceof ReservedMapping) {
            Vector<VirtualMapping> cm = ((ReservedMapping)parent).committedMappings();
            return (cm == null) ? 0 : cm.size();
        } else {
            return 0;
        }
    }

    @Override
    public int getIndexOfChild(Object parent, Object child) {
        if (parent == ROOT) {
            return processes.indexOf(child);
        } else if (parent instanceof Process) {
            return (child instanceof Pmap) ? 0 : 1;
        } else if (parent instanceof Pmap) {
            return virtualMappings.get(((Pmap)parent).process().pid()).indexOf(child);
        } else if (parent instanceof NMT) {
            return nmtMappings.get(((NMT)parent).process().pid()).indexOf(child);
        } else if (parent instanceof ReservedMapping) {
            return ((ReservedMapping)parent).committedMappings().indexOf(child);
        } else {
            return 0;
        }
    }

    @Override
    public Object getRoot() {
        return ROOT;
    }

    @Override
    public boolean isLeaf(Object node) {
        if (node instanceof ReservedMapping) {
            return ((ReservedMapping)node).committedMappings() == null;
        } else if (node instanceof VirtualMapping) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void addTreeModelListener(TreeModelListener l) {
        // empty for read-only model
    }
    @Override
    public void removeTreeModelListener(TreeModelListener l) {
        // empty for read-only model
    }
    @Override
    public void valueForPathChanged(TreePath path, Object newValue) {
        // empty for read-only model
    }

    private String toHTML(String head, long virt, long rss, long reloaded) {
        StringBuffer sb = new StringBuffer("<html><pre>" + head);
        sb.append("<hr/>");
        sb.append(String.format("virtual: %7dkb<br/>", virt / 1024));
        sb.append(String.format("    rss: %7dkb<br/>", rss / 1024));
        sb.append(String.format("   uffd: %7dkb</pre></html>", reloaded / 1024));
        return sb.toString();
    }
    public String getToolTipText(Object node, String... info) {
        return switch (node) {
            case VirtualMapping vm -> {
                String head = vm.info();
                if (vm instanceof ReservedMapping) {
                    head = String.format("%#018x-%#018x", vm.start(), vm.end());
                }
                yield toHTML(head, vm.size(), vm.rss(), vm.reloaded());
            }
            case Process pr -> {
                // Return the Pmap values for a process
                yield getToolTipText(getChild(pr, 0), pr.toString() + "/");
            }
            case Memory mem -> {
                if (mem.virt == 0) {
                    int mappings = getChildCount(mem);
                    for (int m = 0; m < mappings; m++) {
                        VirtualMapping vm = (VirtualMapping)getChild(mem, m);
                        mem.virt += vm.size();
                        mem.rss += vm.rss();
                        mem.reloaded += vm.reloaded();
                    }
                }
                yield toHTML((info.length == 0 ? "" : info[0]) + mem.toString(),
                             mem.virt, mem.rss, mem.reloaded);
            }
            default -> null;
        };
    }
    public boolean contains(Object node, long virtAddr) {
        return switch (node) {
            case VirtualMapping vm -> vm.contains(virtAddr);
            case NMT nmt -> {
                int mappings = getChildCount(nmt);
                for (int m = 0; m < mappings; m++) {
                    VirtualMapping vm = (VirtualMapping)getChild(nmt, m);
                    if (vm.contains(virtAddr)) {
                        yield true;
                    }
                }
                yield false;
            }
            default -> false;
        };
    }
}

final class ReplayThreadState {
    public static final int STOP = 0;
    public static final int PLAY = 1;
    public static final int REWIND = 2;
    public static final int FORWARD = 3;
    public static final int QUIT = 4;
}

class PhysicalMemory extends JPanel {
    private final int pageSize = UffdVisualizer.pageSize, width = UffdVisualizer.width, scale = UffdVisualizer.scale;
    private BufferedImage image;
    private PhysicalMapping physicalMapping;
    private HashMap<Integer, TreeMap<Long, Long>> v2pMappings;
    private HashMap<Integer, String> processMapping;

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
        ArrayList<PidVirtual> pids = physicalMapping.getPidVirtual(address);
        StringBuilder sb = new StringBuilder("<html>");
        sb.append(String.format("%#018x<br/>", address));
        if (pids != null && pids.size() > 0) {
            for (PidVirtual pv : pids) {
                int pid = pv.pid();
                sb.append(String.format("%d: %s (%#018x)<br/>", pid, processMapping.get(pid), pv.virtual()));
            }
        }
        if (physicalMapping.isReloaded(address)) {
            Byte uffdFlags = physicalMapping.getUffdFlags(address);
            sb.append("<hr/>");
            sb.append(String.format("uffd event: %s<br/>", ((uffdFlags & UffdFlags.WRITE) > 0) ? "write" : "read" ));
            sb.append(String.format("uffd action: %s", ((uffdFlags & UffdFlags.LOAD) > 0) ? "load" : "zero" ));
        }
        return sb.append("</html>").toString();
    }


    public void paint(Graphics g) {
        super.paint(g);
        g.drawImage(image, 0, 0, null);
    }
}



class PhysicalViewPanel extends JPanel implements TreeSelectionListener, ActionListener, MouseListener {
    private PhysicalMapping physicalMapping;
    private HashMap<Integer, TreeMap<Long, Long>> v2pMappings;
    private UffdState uffdState;
    private int uffdIndex;
    private JTree processTree;
    private PhysicalMemory physicalMemory;
    private JButton rewindButton, forwardButton, playButton;
    private JFormattedTextField uffdField;
    private ImageIcon playIcon, pauseIcon;
    private BufferedImage baseImage, pidImage, uffdImage;
    private Thread replayThread;
    private volatile int replayState = ReplayThreadState.STOP;
    //private int pid;

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
        final int pageSize = UffdVisualizer.pageSize, width = UffdVisualizer.width, scale = UffdVisualizer.scale;
        BufferedImage baseImage = createEmptyImage();
        Graphics2D g2d = baseImage.createGraphics();
        g2d.setBackground(Colors.BACKGROUND);
        g2d.clearRect(0, 0, baseImage.getWidth(), baseImage.getHeight());
        g2d.setColor(Colors.MEMORY);
        for (long address : physicalMapping.keySet()) {
            int x = (((int)(address % (pageSize * width))) / pageSize) * scale;
            int y = (int)(address / (pageSize * width)) * scale;
            g2d.drawRect(x, y, scale - 1, scale - 1);
        }
        return baseImage;
    }

    private BufferedImage createPidImage(BufferedImage baseImage, PhysicalMapping physicalMapping,
                                         HashMap<Integer, TreeMap<Long, Long>> v2pMappings) {
        final int pageSize = UffdVisualizer.pageSize, width = UffdVisualizer.width, scale = UffdVisualizer.scale;
        Object vm = getSelectedVirtualMapping();
        int pid = getSelectedPid();
        MemMapTreeModel tm = (MemMapTreeModel)processTree.getModel();
        BufferedImage pidImage = createEmptyImage();
        Graphics2D g2d = pidImage.createGraphics();
        g2d.drawImage(baseImage, 0, 0, null);
        for (var entry : v2pMappings.get(pid).entrySet()) {
            long virtAddr = entry.getKey();
            if (vm == null || tm.contains(vm, virtAddr)) {
                long physAddr = entry.getValue();
                int x = (((int)(physAddr % (pageSize * width))) / pageSize) * scale;
                int y = (int)(physAddr / (pageSize * width)) * scale;
                if (physicalMapping.getPidVirtual(physAddr).size() == 1) {
                    g2d.setColor(Colors.SELECTED);
                } else {
                    g2d.setColor(Colors.SHARED);
                }
                g2d.drawRect(x, y, scale - 1, scale - 1);
            }
        }
        return pidImage;
    }

    public PhysicalViewPanel(HashMap<Integer, String> processMapping,
                             HashMap<Integer, Vector<VirtualMapping>> virtualMappings,
                             HashMap<Integer, Vector<VirtualMapping>> nmtMappings,
                             HashMap<Integer, TreeMap<Long, Long>> v2pMappings,
                             PhysicalMapping physicalMapping,
                             UffdState uffdState) {
        super(new BorderLayout());
        this.physicalMapping = physicalMapping;
        this.v2pMappings = v2pMappings;
        this.uffdState = uffdState;
        MemMapTreeModel treeModel = new MemMapTreeModel(processMapping, virtualMappings, nmtMappings);
        processTree = new JTree(treeModel);
        processTree.setFont(new Font(Font.MONOSPACED, Font.PLAIN, processTree.getFont().getSize()));
        processTree.setRootVisible(false);
        processTree.setShowsRootHandles(true);
        processTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        processTree.setEditable(false);
        processTree.addTreeSelectionListener(this);
        processTree.addMouseListener(this);
        processTree.setCellRenderer(new DefaultTreeCellRenderer() {
            {
                setClosedIcon(null);
                setOpenIcon(null);
                setLeafIcon(null);
            }
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                          boolean leaf, int row, boolean hasFocus) {
                final Component rc = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                String toolTipText = ((MemMapTreeModel)tree.getModel()).getToolTipText(value);
                this.setToolTipText(toolTipText);
                return rc;
            }
        });
        DefaultTreeCellRenderer dtcr = (DefaultTreeCellRenderer)processTree.getCellRenderer();
        dtcr.setClosedIcon(null);
        dtcr.setOpenIcon(null);
        dtcr.setLeafIcon(null);
        JScrollPane processTreeScrollPane = new JScrollPane(processTree);
        processTree.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        ToolTipManager.sharedInstance().registerComponent(processTree);

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
                          uffdState.uffdEntries, uffdState.uffdLoading, (uffdState.uffdLoading * UffdVisualizer.pageSize) / 1024,
                          uffdState.uffdZeroing, (uffdState.uffdZeroing * UffdVisualizer.pageSize) / 1024));
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
        this.add(processTreeScrollPane, BorderLayout.LINE_END);
        this.add(controlPanel, BorderLayout.PAGE_END);
        // Start with kernel pages selected
        processTree.setSelectionRow(1);
    }

    private Object getSelectedVirtualMapping() {
        TreePath tp = processTree.getSelectionPath();
        if (tp == null) {
          return null;
        }
        return switch (tp.getLastPathComponent()) {
            case VirtualMapping vm -> vm;
            case MemMapTreeModel.NMT nmt -> nmt;
            default -> null;
        };
    }

    private int getSelectedPid() {
        TreePath tp = processTree.getSelectionPath();
        if (tp == null) {
          return 0; // Always safe because kernel uses pid 0
        }
        return ((MemMapTreeModel.Process)tp.getPathComponent(1)).pid();
    }

    @Override
    public void valueChanged(TreeSelectionEvent e) {
        pidImage = createPidImage(baseImage, physicalMapping, v2pMappings);
        physicalMemory.setImage(pidImage);
        playButton.setActionCommand("play");
        playButton.setIcon(playIcon);
        replayThread = null;
        uffdIndex = 0;
        uffdField.setValue(uffdIndex);
    }

    @Override
    public void mouseClicked(MouseEvent e) {
    }
    @Override
    public void mouseEntered(MouseEvent e) {
    }
    @Override
    public void mouseExited(MouseEvent e) {
    }
    @Override
    public void mousePressed(MouseEvent e) {
        if (e.getComponent() == processTree) {
            // Hack to resize the JTree's JScrollPane once the JTree is expanded or collapsed
            this.revalidate();
        }
    }
    @Override
    public void mouseReleased(MouseEvent e) {
    }

    private void drawPage(Graphics2D g2d, int uffdIndex) {
        final int pageSize = UffdVisualizer.pageSize, width = UffdVisualizer.width, scale = UffdVisualizer.scale;
        Object vm = getSelectedVirtualMapping();
        int pid = getSelectedPid();
        MemMapTreeModel tm = (MemMapTreeModel)processTree.getModel();
        long address = uffdState.uffdPhysical[uffdIndex];
        int x = (((int)(address % (pageSize * width))) / pageSize) * scale;
        int y = (int)(address / (pageSize * width)) * scale;
        ArrayList<PidVirtual> pids = physicalMapping.getPidVirtual(address);
        boolean selected = false;
        if (pids != null) {
            for (PidVirtual pv : pids) {
                if (pv.pid() == pid) {
                    if (vm == null || tm.contains(vm, pv.virtual())) {
                        selected = true;
                    }
                    break;
                }
            }
            if (selected) {
                g2d.setColor(Colors.LOADED_SELECTED);
            } else {
                g2d.setColor(Colors.LOADED);
            }
        } else {
            g2d.setColor(Colors.NEW);
        }
        g2d.drawRect(x, y, scale - 1, scale - 1);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (e.getSource().equals(playButton)) {
            if ("play".equals(playButton.getActionCommand())) {
                playButton.setActionCommand("pause");
                playButton.setIcon(pauseIcon);
                if (replayThread == null) {
                    uffdImage = createPidImage(baseImage, physicalMapping, v2pMappings);
                    Graphics2D g2d = uffdImage.createGraphics();
                    uffdIndex = 0;
                    replayThread = new Thread() {
                        public void run() {
                            while (true) {
                                switch (replayState) {
                                    case ReplayThreadState.PLAY : {
                                        if (uffdIndex++ < uffdState.uffdEntries) {
                                            drawPage(g2d, uffdIndex);
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
                                    case ReplayThreadState.QUIT : {
                                        playButton.setActionCommand("play");
                                        playButton.setIcon(playIcon);
                                        replayState = ReplayThreadState.STOP;
                                        return;
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
            replayState = ReplayThreadState.QUIT;
            replayThread = null;
            uffdIndex = 0;
            uffdField.setValue(uffdIndex);
            physicalMemory.setImage(createPidImage(baseImage, physicalMapping, v2pMappings));
        } else if (e.getSource().equals(forwardButton)) {
            uffdImage = createPidImage(baseImage, physicalMapping, v2pMappings);
            Graphics2D g2d = uffdImage.createGraphics();
            for (int u = 0; u < uffdState.uffdEntries; u++) {
                drawPage(g2d, u);
                uffdField.setValue(uffdState.uffdEntries);
                physicalMemory.setImage(uffdImage);
            }
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
    // Per Java process list of NMT mappings
    private HashMap<Integer, Vector<VirtualMapping>> nmtMappings;
    // Per process list of all virtual to physical mappings
    private HashMap<Integer, TreeMap<Long, Long>> v2pMappings;
    // Pid to executable mapping
    private HashMap<Integer, String> processMapping;
    private UffdState uffdState;

    static class ProcessMappingsState {
        int pid;
    }
    private void processMappingsLine(ProcessMappingsState pms, String line) {
        String fields[] = line.split(" ");
        if ("=".equals(fields[0])) {
            pms.pid = Integer.parseInt(fields[1]);
            processMapping.put(pms.pid, fields[2]);
            virtualMappings.put(pms.pid, new Vector<VirtualMapping>());
            v2pMappings.put(pms.pid, new TreeMap<Long, Long>());
        } else if ("v".equals(fields[0])) {
            String info = null;
            if (fields.length > 3) {
                info = line.substring(line.indexOf(fields[3]));
            }
            virtualMappings.get(pms.pid).add(
                new VirtualMapping(
                    Long.parseUnsignedLong(fields[1], 2, 18, 16),
                    Long.parseUnsignedLong(fields[2], 2, 18, 16),
                    info));
        } else if ("p".equals(fields[0])) {
            long virtual = Long.parseUnsignedLong(fields[1], 2, 18, 16);
            long physical = Long.parseUnsignedLong(fields[2], 2, 18, 16);
            if (pms.pid <= 0) {
                // Kernel addresses are all physical addresses
                physical = virtual;
            }
            physicalMapping.put(physical, pms.pid, virtual);
            v2pMappings.get(pms.pid).put(virtual, physical);
        }
    }

    private void processUffdLine(UffdState uffdState, PhysicalMapping physicalMapping, String line) {
        // A line in the uffd log file looks as follows:
        // UFFD_EVENT_PAGEFAULT (r): 0x00007fffbbaa4000 0x00007fffbbaa4000  Loading: 0x0000000003cb5000 - 0x0000000003cb6000
        String fields[] = line.split(" +");
        if (fields.length < 6 || !fields[0].startsWith("UFFD_EVENT")) {
            return;
        }
        if ("UFFD_EVENT_PAGEFAULT".equals(fields[0])) {
            int entry = uffdState.uffdEntries++;
            long address = Long.parseUnsignedLong(fields[5], 2, 18, 16);
            uffdState.uffdPhysical[entry] = address;
            uffdState.uffdFlags[entry] |= UffdFlags.PAGE | UffdFlags.SET;
            if ("(w):".equals(fields[1])) {
                uffdState.uffdFlags[entry] |= UffdFlags.WRITE;
            }
            if ("Loading:".equals(fields[4])) {
                uffdState.uffdFlags[entry] |= UffdFlags.LOAD;
                uffdState.uffdLoading++;
            } else {
                uffdState.uffdZeroing++;
            }
            physicalMapping.put(address, uffdState.uffdFlags[entry]);
        }
    }

    static class NMTLogParserState {
        static final int INIT = 0;
        static final int PID = 1;
        static final int VIRTUAL = 2;
        static final int END = 3;
        int state = INIT;
        int pid = 0;
        Vector<VirtualMapping> vm;
        Matcher matcher = Pattern.compile("\t\\[0x(\\p{XDigit}+) - 0x(\\p{XDigit}+)\\] (committed [^ ]+) (?:from)?$").matcher("");
        Matcher reservedMatcher = Pattern.compile("\\[0x(\\p{XDigit}+) - 0x(\\p{XDigit}+)\\] .+ for (.+) from").matcher("");
    }
    private void processNMTLine(NMTLogParserState ps, String line) {
        if (ps.state == NMTLogParserState.INIT) {
            try {
                ps.pid = Integer.parseInt(line.substring(0, line.indexOf(':')));
            } catch (NumberFormatException nfe) {
                System.err.println("NMT Parse Error: NMT file ust start with a '<pid>:' line.");
            }
            ps.state = NMTLogParserState.PID;
        }
        else if (ps.state == NMTLogParserState.PID) {
            if ("Virtual memory map:".equals(line)) {
                ps.state = NMTLogParserState.VIRTUAL;
                ps.vm = new Vector<>();
            }
        }
        else if (ps.state == NMTLogParserState.VIRTUAL) {
            VirtualMapping vm;
            if (line.startsWith("[0x") && ps.reservedMatcher.reset(line).matches()) {
                vm = new ReservedMapping(Long.parseUnsignedLong(ps.reservedMatcher.group(1), 16),
                                         Long.parseUnsignedLong(ps.reservedMatcher.group(2), 16),  ps.reservedMatcher.group(3));
                vm.setPhysicalState(v2pMappings.get(ps.pid), physicalMapping);
                ps.vm.add(vm);
            } else if (line.startsWith("\t") && ps.matcher.reset(line).matches()) {
                vm = new VirtualMapping(Long.parseUnsignedLong(ps.matcher.group(1), 16),
                                        Long.parseUnsignedLong(ps.matcher.group(2), 16),  ps.matcher.group(3));
                vm.setPhysicalState(v2pMappings.get(ps.pid), physicalMapping);
                ReservedMapping rm = (ReservedMapping)ps.vm.lastElement();
                rm.addCommittedMapping(vm);
            } else if (line.startsWith("Details:")) {
                ps.state = NMTLogParserState.END;
            }
        }
    }

    public UffdVisualizer(File mappings, File uffd, File nmt) {
        physicalMapping = new PhysicalMapping();
        virtualMappings = new HashMap<Integer, Vector<VirtualMapping>>();
        nmtMappings = new HashMap<Integer, Vector<VirtualMapping>>();
        v2pMappings = new HashMap<Integer, TreeMap<Long, Long>>();
        processMapping = new HashMap<Integer, String>();
        long start = System.currentTimeMillis();
        try {
            ProcessMappingsState pms = new ProcessMappingsState();
            Files.lines(mappings.toPath()).forEach(l -> processMappingsLine(pms, l));
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
            uffdState = new UffdState(new long[(int)uffdLength], new byte[(int)uffdLength]);
            Files.lines(uffd.toPath()).forEach(l -> processUffdLine(uffdState, physicalMapping, l));
            long parsedUffd = System.currentTimeMillis();

            System.out.println(String.format("Parsed %d UFFD events (%d pages / %dkb loaded,  %d pages / %dkb zeroed) in %dms.",
                                             uffdState.uffdEntries, uffdState.uffdLoading, (uffdState.uffdLoading * pageSize) / 1024,
                                             uffdState.uffdZeroing, (uffdState.uffdZeroing * pageSize) / 1024,
                                             parsedUffd - parsedMappings));

            for (var entry : virtualMappings.entrySet()) {
                int pid = entry.getKey();
                for (var vm : entry.getValue()) {
                    vm.setPhysicalState(v2pMappings.get(pid), physicalMapping);
                }
            }
            if (nmt != null) {
                final NMTLogParserState ps = new NMTLogParserState();
                Files.lines(nmt.toPath()).forEach(l -> processNMTLine(ps, l));
                System.out.println(String.format("Parsed %d NMT mappings for Java process %d processes in %dms.",
                                                 ps.vm.size(), ps.pid, System.currentTimeMillis() - parsedUffd));
                nmtMappings.put(ps.pid, ps.vm);
            }
        } catch (IOException ioe) {
            System.err.println(ioe);
            System.exit(-1);
        }
    }

    public void createFrame() {
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Physical View",
                          new PhysicalViewPanel(processMapping, virtualMappings, nmtMappings, v2pMappings, physicalMapping, uffdState));
        JFrame frame = new JFrame();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().add(tabbedPane);
        frame.pack();
        frame.setVisible(true);
    }

    private static void help() {
        System.out.println("\nio.simonis.UffdVisualizer <mapings-file> <uffd-file> [nmt-file]\n");
        System.exit(-1);
    }
    public static void main(String args[]) {
        if (args.length < 2 || args.length > 3) {
            help();
        }
        File mappings = new File(args[0]);
        if (!mappings.canRead()) {
            System.err.println("Can't read " + mappings);
            System.exit(-1);
        }
        File uffd = new File(args[1]);
        if (!uffd.canRead()) {
            System.err.println("Can't read " + uffd);
            System.exit(-1);
        }
        File nmt = null;
        if (args.length == 3) {
            nmt = new File(args[2]);
            if (!nmt.canRead()) {
                System.err.println("Can't read " + nmt);
                System.exit(-1);
            }
        }
        ToolTipManager.sharedInstance().setDismissDelay(Integer.MAX_VALUE);
        ToolTipManager.sharedInstance().setInitialDelay(0);
        UffdVisualizer uffdVisualizer = new UffdVisualizer(mappings, uffd, nmt);
        uffdVisualizer.createFrame();
    }
}
