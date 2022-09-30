package io.simonis;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.Vector;

class PidVirtual {
    private int pid;
    private long virtual;
    public PidVirtual(int pid, long virtual) {
        this.pid = pid;
        this.virtual = virtual;
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
public class UffdVisualizer {
    // Global list of all mapped phiscal pages
    private PhysicalMapping physicalMapping;
    // Per process list of all virtual mappings
    private HashMap<Integer, Vector<VirtualMapping>> virtualMappings;
    // Per proecss list of all virtual to physical mappings
    private HashMap<Integer, TreeMap<Long, Long>> physicalMappings;
    // Pid to executable mapping
    private HashMap<Integer, String> processMapping;
    private int pid;

    private void processMappingsLine(String line) {
        String fields[] = line.split(" ");
        if ("=".equals(fields[0])) {
            pid = Integer.parseInt(fields[1]);
            processMapping.put(pid, fields[2]);
            virtualMappings.put(pid, new Vector<VirtualMapping>());
            physicalMappings.put(pid, new TreeMap<Long, Long>());
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
            physicalMappings.get(pid).put(virtual, physical);
        }
    }
    public UffdVisualizer(File mappings, File uffd) {
        long start = System.currentTimeMillis();
        physicalMapping = new PhysicalMapping();
        virtualMappings = new HashMap<Integer, Vector<VirtualMapping>>();
        physicalMappings = new HashMap<Integer, TreeMap<Long, Long>>();
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
    }
}