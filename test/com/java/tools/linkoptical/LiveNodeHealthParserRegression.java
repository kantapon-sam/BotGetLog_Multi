package com.java.tools.linkoptical;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Manual regression probe for Nokia, ZTE and Huawei live port parsing. */
public final class LiveNodeHealthParserRegression {

    private LiveNodeHealthParserRegression() {
    }

    public static void main(String[] args) throws Exception {
        if (args == null || args.length != 3) {
            throw new IllegalArgumentException("Expected Nokia, ZTE and Huawei log paths.");
        }
        verify(args[0], "N-LLDP-Link_OPTIC", "Nokia");
        verify(args[1], "ZTE-LLDP-Link_OPTIC", "ZTE");
        verify(args[2], "HW-LLDP-Link_OPTIC", "Huawei");
        verifyHuaweiCompactLiveTranscript();
    }

    private static void verifyHuaweiCompactLiveTranscript() {
        String transcript = "<LIVE>display interface description\n"
                + "Interface                      PHY     Protocol Description\n"
                + "100GE1/0/0                    up      up       To_AGN-A\n"
                + "100GE1/0/1                    *down   down     Spare\n"
                + "<LIVE>display interface 100GE1/0/0\n"
                + "100GE1/0/0 current state : UP (ifindex: 527)\n"
                + "Line protocol current state : UP\n"
                + "Description: To_AGN-A\n"
                + "Port BW: 100G, Transceiver max BW: 100G\n"
                + "Connector Type: LC, Transmission Distance: 10km\n"
                + "WaveLength: 1295.56nm\n"
                + "Rx Warning range: [-10.604, 4.499]dBm\n"
                + "Rx0 Power: -1.00dBm, Tx0 Power: 1.50dBm\n"
                + "Rx1 Power:  1.00dBm, Tx1 Power: 2.50dBm\n"
                + "Rx2 Power: -2.00dBm, Tx2 Power: 1.50dBm\n"
                + "Rx3 Power:  2.00dBm, Tx3 Power: 2.50dBm\n"
                + "<LIVE>\n";
        List<LiveNodeHealthParser.PortSnapshot> ports
                = LiveNodeHealthParser.parsePorts("AGN-LIVE", "10.0.0.1",
                        "HW-LLDP-Link_OPTIC", transcript);
        if (ports.size() != 2) {
            throw new AssertionError("Huawei compact live parser expected 2 ports, got " + ports.size());
        }
        if (!"UP".equals(ports.get(0).portStatus) || !ports.get(0).hasOpticalData) {
            throw new AssertionError("Huawei active port detail was not merged with the compact inventory.");
        }
        if (Math.abs(ports.get(0).txPowerDbm.doubleValue() - 2.0d) > 0.001d
                || Math.abs(ports.get(0).rxPowerDbm.doubleValue()) > 0.001d) {
            throw new AssertionError("Huawei multi-lane Tx/Rx values were not averaged.");
        }
        if (!"DOWN".equals(ports.get(1).portStatus)) {
            throw new AssertionError("Huawei compact inventory did not retain the down port.");
        }
        System.out.println("Huawei compact live transcript: ports=" + ports.size());
    }

    private static void verify(String path, String cmdSet, String vendor) throws Exception {
        String text = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        List<LiveNodeHealthParser.PortSnapshot> ports
                = LiveNodeHealthParser.parsePorts("LIVE-" + vendor, "10.0.0.1", cmdSet, text);
        int optical = 0;
        int up = 0;
        for (LiveNodeHealthParser.PortSnapshot port : ports) {
            if (port.hasOpticalData) optical++;
            if ("UP".equals(port.portStatus)) up++;
        }
        if (ports.isEmpty() || optical == 0) {
            throw new AssertionError(vendor + " live parser returned no usable port/optical rows.");
        }
        System.out.println(vendor + ": ports=" + ports.size() + ", up=" + up + ", optical=" + optical);
    }
}
