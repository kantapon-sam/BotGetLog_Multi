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
        verifyNokiaCompactLiveTranscript();
        verifyHuaweiCompactLiveTranscript();
    }

    private static void verifyNokiaCompactLiveTranscript() {
        String transcript = "<LIVE>show port\n"
                + "Port          Admin Link Port    Cfg  Oper LAG/ Port Port Port   C/QS/S/XFP/\n"
                + "Id            State      State   MTU  MTU  Bndl Mode Encp Type   MDIMDX\n"
                + "-------------------------------------------------------------------------------\n"
                + "1/1/1         Up    Yes  Up      9212 9212    - netw null xcme   GIGE-LX  10KM\n"
                + "1/1/2         Up    No   Down    9212 9212    - accs qinq xgige  10GBASE-LR  *\n"
                + "8/1/c1/1      Up    Yes  Up      9212 9212    - netw null c100g  100GBASE-LR4 *\n"
                + "A/1           Up    No   Down    1514 1514    - netw null faste\n"
                + "A:NOKIA-LIVE#show port 1/1/1\n"
                + "Description        : To_DN-B\n"
                + "Interface          : 1/1/1                      Oper Speed       : 1 Gbps\n"
                + "Oper State         : up\n"
                + "TX Laser Wavelength: 1310 nm\n"
                + "Link Length support: 10km for SMF\n"
                + "Tx Output Power (dBm)         -5.24     -2.00      -3.00      -9.00     -10.00\n"
                + "Rx Optical Power (avg dBm)    -0.87     -2.00!     -3.00!    -21.02     -22.01\n"
                + "A:NOKIA-LIVE#show port description\n"
                + "Port Id        Description\n"
                + "1/1/1          To_DN-B\n"
                + "1/1/2          Reserved_For_Rehoming\n";
        List<LiveNodeHealthParser.PortSnapshot> ports
                = LiveNodeHealthParser.parsePorts("NOKIA-LIVE", "10.0.0.1",
                        "N-LLDP-Link_OPTIC", transcript);
        if (ports.size() != 4) {
            throw new AssertionError("Nokia compact live parser expected 4 ports, got " + ports.size());
        }
        if (!"UP".equals(ports.get(0).portStatus) || !"1G".equals(ports.get(0).speed)) {
            throw new AssertionError("Nokia 1G live port state/speed was not parsed.");
        }
        if (!ports.get(0).hasOpticalData
                || Math.abs(ports.get(0).txPowerDbm.doubleValue() + 5.24d) > 0.001d
                || Math.abs(ports.get(0).rxPowerDbm.doubleValue() + 0.87d) > 0.001d) {
            throw new AssertionError("Nokia live Tx/Rx detail was not merged into the port row.");
        }
        if (!"ALARM".equals(ports.get(0).opticalStatus)
                || !"[-21.02<>-3.00]".equals(ports.get(0).rxWarningRange)) {
            throw new AssertionError("Nokia alarm-marked Rx thresholds were not parsed.");
        }
        if (!"DOWN".equals(ports.get(1).portStatus) || !"10G".equals(ports.get(1).speed)) {
            throw new AssertionError("Nokia 10G down port was not parsed.");
        }
        if (!"Reserved_For_Rehoming".equals(ports.get(1).description)) {
            throw new AssertionError("Nokia compact port description was not merged.");
        }
        if (!"UP".equals(ports.get(2).portStatus) || !"100G".equals(ports.get(2).speed)) {
            throw new AssertionError("Nokia connector port state/speed was not parsed.");
        }
        if (!"DOWN".equals(ports.get(3).portStatus) || !"100M".equals(ports.get(3).speed)) {
            throw new AssertionError("Nokia management port state/speed was not parsed.");
        }
        System.out.println("Nokia compact live transcript: ports=" + ports.size());
    }

    private static void verifyHuaweiCompactLiveTranscript() {
        String transcript = "<LIVE>display interface description\n"
                + "Interface                      PHY     Protocol Description\n"
                + "100GE1/0/0                    up      up       To_AGN-A\n"
                + "100GE1/0/1                    *down   down     Spare\n"
                + "GE0/1/0(10G)                 up      up       To_DN-B\n"
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
        if (ports.size() != 3) {
            throw new AssertionError("Huawei compact live parser expected 3 ports, got " + ports.size());
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
        if (!"UP".equals(ports.get(2).portStatus) || !"10G".equals(ports.get(2).speed)
                || !"GE0/1/0".equals(ports.get(2).port)) {
            throw new AssertionError("Huawei parenthesized port speed was not normalized.");
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
