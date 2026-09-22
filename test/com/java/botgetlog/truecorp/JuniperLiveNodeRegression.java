package com.java.botgetlog.truecorp;

import com.java.tools.linkoptical.CpuMemoryExporter;
import com.java.tools.linkoptical.LiveNodeHealthParser;
import java.util.Arrays;
import java.util.List;

/** Offline MX2020 live probe command and transcript regression. */
public final class JuniperLiveNodeRegression {
    public static void main(String[] args) {
        List<String> expected = Arrays.asList(
                "set cli screen-length 0", "show chassis routing-engine",
                "show interfaces terse", "show interfaces descriptions",
                "show interfaces extensive | match \"Physical interface|CRC/Align errors|Speed:|Description:\"",
                "show interfaces diagnostics optics * | match \"Physical interface|Laser (output|receiver) power +:|Laser rx power .*warning threshold\"",
                "show lldp neighbors detail");
        List<String> actual = TrueLiveNodeProbeCli.commandsFor(
                "J-LLDP-Link_OPTIC", "all", "HAMMBKBD1KW", "MX2020", null);
        require(expected.equals(actual), "Juniper all-metrics command list: " + actual);
        String transcript = "user@HAMMBKBD1KW> show chassis routing-engine\n"
                + "Routing Engine status:\n"
                + "  Slot 0:\n    Current state                  Backup\n"
                + "    DRAM                           16351 MB (16384 MB installed)\n"
                + "    Memory utilization             11 percent\n"
                + "    CPU utilization:\n      Idle                         99 percent\n"
                + "  Slot 1:\n    Current state                  Master\n"
                + "    DRAM                           16351 MB (16384 MB installed)\n"
                + "    Memory utilization             25 percent\n"
                + "    CPU utilization:\n      User                          7 percent\n"
                + "      Idle                         92 percent\n"
                + "user@HAMMBKBD1KW> show interfaces terse\n"
                + "et-4/0/0                up    up\n"
                + "et-4/0/0.0              up    up inet\n"
                + "xe-4/0/1                up    down\n"
                + "user@HAMMBKBD1KW> show interfaces descriptions\n"
                + "et-4/0/0                up    up   To-Core\n"
                + "xe-4/0/1                up    down Spare\n"
                + "user@HAMMBKBD1KW> show interfaces extensive\n"
                + "Physical interface: et-4/0/0, Enabled, Physical link is Up\n"
                + "  Link-level type: Ethernet, MTU: 9192, Speed: 100Gbps\n"
                + "  CRC/Align errors                   7                2\n"
                + "Physical interface: xe-4/0/1, Enabled, Physical link is Down\n"
                + "  Link-level type: Ethernet, MTU: 9192, Speed: 10Gbps\n"
                + "  CRC/Align errors: 0\n"
                + "user@HAMMBKBD1KW> show interfaces diagnostics optics *\n"
                + "Physical interface: et-4/0/0\n"
                + "  Laser output power: 1.000 mW / 0.00 dBm\n"
                + "  Laser rx power: 0.316 mW / -5.00 dBm\n"
                + "  Laser output power: 1.585 mW / 2.00 dBm\n"
                + "  Laser receiver power: 0.501 mW / -3.00 dBm\n"
                + "  Laser rx power low warning threshold: 0.100 mW / -10.00 dBm\n"
                + "  Laser rx power high warning threshold: 1.000 mW / 0.00 dBm\n"
                + "user@HAMMBKBD1KW>\n";
        CpuMemoryExporter.CpuMemorySnapshot cpu = CpuMemoryExporter.parseSnapshot(
                "HAMMBKBD1KW", "10.185.0.11", transcript);
        require(cpu.cpuCurrentPercent != null && cpu.cpuCurrentPercent == 8.0d,
                "master RE CPU");
        require(cpu.memoryTotalMb != null && cpu.memoryTotalMb == 16384.0d
                && cpu.memoryUsedMb != null && cpu.memoryUsedMb == 4096.0d,
                "master RE memory");
        List<LiveNodeHealthParser.PortSnapshot> ports = LiveNodeHealthParser.parsePorts(
                "HAMMBKBD1KW", "10.185.0.11", "J-LLDP-Link_OPTIC", transcript);
        require(ports.size() == 2, "physical ports only: " + ports.size());
        LiveNodeHealthParser.PortSnapshot first = ports.get(0);
        require("et-4/0/0".equals(first.port) && "UP".equals(first.portStatus)
                && "To-Core".equals(first.description) && "100Gbps".equals(first.speed)
                && Long.valueOf(7).equals(first.crcInput)
                && Long.valueOf(2).equals(first.crcOutput)
                && Long.valueOf(9).equals(first.crcTotal)
                && first.hasOpticalData && first.rxPowerDbm == -4.0d
                && first.txPowerDbm == 1.0d && "NORMAL".equals(first.opticalStatus),
                "first port status, CRC and optics");
        require("DOWN".equals(ports.get(1).portStatus)
                && !ports.get(1).hasOpticalData, "second port isolation");
        require("J".equals(Telnet_Multi.detectVendorFromPrompt(
                "vdes2442@clls@HAMMBKBD02W_re0>", "HW", false)), "Junos prompt detected");
        require("HAMMBKBD02W_re0".equals(Telnet_Multi.extractNodeNameFromPromptToken(
                "vdes2442@clls@HAMMBKBD02W_re0>")), "Junos prompt node name");
        System.out.println("Juniper MX2020 live regression: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
