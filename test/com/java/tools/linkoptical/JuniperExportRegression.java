package com.java.tools.linkoptical;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class JuniperExportRegression {
    private static final String P = "user@clls@CN_TEST_re0> ";
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("juniper-export-");
        try {
            String text = P + "set cli screen-length 0\n"
                    + P + "show version\nHostname: CN_TEST_re0\nModel: mx2020\nJunos: 21.2R3-S3.5\n"
                    + P + "show lldp neighbors detail\n"
                    + peer("et-0/0/0", "CORE_LONG_HOSTNAME_re0", "et-3/0/0")
                    + peer("et-0/0/0", "SECOND_CORE_NAME", "Ethernet1/1")
                    + P + "show chassis routing-engine\nRouting Engine status:\nSlot 0:\nCurrent state Backup\nDRAM 16315 MB (16384 MB installed)\nMemory utilization 10 percent\nCPU utilization:\nIdle 99 percent\n"
                    + "Slot 1:\nCurrent state Master\nDRAM 16317 MB (16384 MB installed)\nMemory utilization 25 percent\n5 sec CPU utilization:\nIdle 72 percent\n1 min CPU utilization:\nIdle 67 percent\n"
                    + P + "show interfaces terse\net-0/0/0 up up\net-0/0/0.0 up up inet\net-0/0/1 down down\nxe-0/0/2 down down\nge-0/0/3 up up\n"
                    + P + "show interfaces extensive\n"
                    + physical("et-0/0/0", "Up", "100Gbps", "") + "  CRC/Align errors 7 2\n  Logical interface et-0/0/0.0\n    Protocol aenet, AE bundle: ae52.0, Generation: 1\n"
                    + physical("ae52", "Up", "200Gbps", "To-Core-Bundle")
                    + physical("et-0/0/1", "Down", "100Gbps", "")
                    + physical("xe-0/0/2", "Down", "10Gbps", "Reserved, future")
                    + physical("ge-0/0/3", "Up", "1000mbps", "")
                    + physical("fxp0", "Up", "1000mbps", "management")
                    + P + "show interfaces diagnostics optics *\nPhysical interface: et-0/0/0\nLaser output power: 1 mW / 0 dBm\nLaser receiver power: 0.5 mW / -3 dBm\nLaser rx power low warning threshold: 0.1 mW / -10 dBm\n"
                    + P + "quit Connection closed by foreign host.\n";
            File input = root.resolve("[1]10.0.0.1_CN-TEST-re0_J-LLDP-Link_OPTIC_2026-10-03.txt").toFile();
            Files.write(input.toPath(), text.getBytes(StandardCharsets.UTF_8));
            Link_Optical.ProcessResult out = Link_Optical.processFiles(new File[]{input}, root.resolve("out").toFile(), false);
            List<String> full = Files.readAllLines(out.getFullLldpFile().toPath(), StandardCharsets.UTF_8);
            require(full.size() == 6, "5 physical rows including two LLDP peers: " + full);
            require(full.get(1).contains("CN_TEST_re0,10.0.0.1,et-0/0/0,UP,,CORE_LONG_HOSTNAME_re0,et-3/0/0,100G,"), "neighbor identity");
            require(full.get(1).endsWith(",ae52,To-Core-Bundle"), "AE membership");
            for (String row : full.subList(1, full.size()))
                require(row.contains(",21.2R3-S3.5,mx2020,"), "own equipment and software from show version");
            require(full.get(1).contains(",0.00,-3.00,-10.0,9,"), "optics and CRC");
            require(!String.join("\n", full).contains("fxp0"), "exclude management interface");
            require(Files.readAllLines(out.getNeighborFile().toPath()).size() == 3, "retain long core peer names in filtered CSV");
            List<String> ports = Files.readAllLines(out.getPortFile().toPath());
            require(ports.size() == 2, "one capacity row");
            require(ports.get(1).equals("CN,CN_TEST_re0,10.0.0.1,1,1,0,0,1,1,0,1,2,1,1,0,0,0"), "port counts, up without description, reservation and peer dedup: " + ports.get(1));
            List<String> cpu = Files.readAllLines(out.getCpuMemoryFile().toPath());
            require(cpu.size() == 2, "CPU row");
            String[] fields = cpu.get(1).split(",");
            require(fields[0].equals("CN_TEST_re0") && Double.parseDouble(fields[2]) == 28.0 && Double.parseDouble(fields[7]) == 25.0, "active RE CPU/memory: " + cpu.get(1));
            require(out.getOutputFiles().size() == 5, "existing five-file contract");
            System.out.println("PASS JuniperExportRegression");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch(IOException e) { throw new UncheckedIOException(e); } });
            }
        }
    }
    private static String peer(String local, String name, String port) {
        return "LLDP Neighbor Information:\nLocal Interface : " + local + "\nNeighbour Information:\nPort type : Interface name\nPort ID : " + port + "\nSystem name : " + name + "\n";
    }
    private static String physical(String port, String state, String speed, String desc) {
        return "Physical interface: " + port + ", Enabled, Physical link is " + state + "\n" + (desc.isEmpty() ? "" : "Description: " + desc + "\n") + "Link-level type: Ethernet, Speed: " + speed + ",\n";
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
