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
        if (args != null && args.length != 0 && args.length != 3) {
            throw new IllegalArgumentException("Expected Nokia, ZTE and Huawei log paths.");
        }
        if (args != null && args.length == 3) {
            verify(args[0], "N-LLDP-Link_OPTIC", "Nokia");
            verify(args[1], "ZTE-LLDP-Link_OPTIC", "ZTE");
            verify(args[2], "HW-LLDP-Link_OPTIC", "Huawei");
        }
        verifyNokiaCompactLiveTranscript();
        verifyNokiaDescriptionSpeedToken();
        verifyZteBriefAndCrcTranscript();
        verifyZtePortClassSpeedOverrides();
        verifyHuaweiCompactLiveTranscript();
        verifyHuaweiCopperDistanceTranscript();
    }

    private static void verifyNokiaDescriptionSpeedToken() {
        String transcript = "<LIVE>show port\n"
                + "10/2/c22/1    Up    Yes  Up      9212 9212    - netw null xcme\n"
                + "A:NOKIA-LIVE#show port description\n"
                + "Port Id        Description\n"
                + "10/2/c22/1     To_CBR0208_DC-SW-02_100G_2/2\n";
        List<LiveNodeHealthParser.PortSnapshot> ports
                = LiveNodeHealthParser.parsePorts("NOKIA-LIVE", "10.0.0.1",
                        "N-LLDP-Link_OPTIC", transcript);
        if (ports.size() != 1 || !"100G".equals(ports.get(0).speed)) {
            throw new AssertionError("Nokia _100G_ description token was not normalized.");
        }
    }

    private static void verifyNokiaCompactLiveTranscript() {
        String transcript = "<LIVE>show port\n"
                + "Port          Admin Link Port    Cfg  Oper LAG/ Port Port Port   C/QS/S/XFP/\n"
                + "Id            State      State   MTU  MTU  Bndl Mode Encp Type   MDIMDX\n"
                + "-------------------------------------------------------------------------------\n"
                + "1/1/1         Up    Yes  Up      9212 9212    - netw null xcme   GIGE-LX  10KM\n"
                + "1/1/2         Up    No   Down    9212 9212    - accs qinq xgige  10GBASE-LR  *\n"
                + "8/1/c1/1      Up    Yes  Up      9212 9212    - netw null c100g  100GBASE-LR4 *\n"
                + "1/1/c2/1      Up    No   Down    9212 9212    - netw null xcme\n"
                + "A/1           Up    No   Down    1514 1514    - netw null faste\n"
                + "10/1/15       Down  No   Down    9212 9212    - netw null xcme   10GBASE-LR  *\n"
                + "10/2/c23/1    Up    No   Down    9212 9212    - netw null xcme\n"
                + "A:NOKIA-LIVE#show port 1/1/1\n"
                + "Description        : To_DN-B\n"
                + "Interface          : 1/1/1                      Oper Speed       : 1 Gbps\n"
                + "Oper State         : up\n"
                + "TX Laser Wavelength: 1310 nm\n"
                + "Link Length support: 10km for SMF\n"
                + "Tx Output Power (dBm)         -5.24     -2.00      -3.00      -9.00     -10.00\n"
                + "Rx Optical Power (avg dBm)    -0.87     -2.00!     -3.00!    -21.02     -22.01\n"
                + "FCS Errors       : 4    Mult Collisions : 0\n"
                + "A:NOKIA-LIVE#show port 10/1/15\n"
                + "Description        : 10/100/Gig Ethernet SFP\n"
                + "Interface          : 10/1/15                    Oper Speed       : N/A\n"
                + "Link-level         : Ethernet                   Config Speed     : 1 Gbps\n"
                + "Oper State         : down\n"
                + "A:NOKIA-LIVE#show port 1/1/c2/1\n"
                + "Description        : 100-Gig Ethernet\n"
                + "Interface          : 1/1/c2/1                   Oper Speed       : N/A\n"
                + "Link-level         : Ethernet                   Config Speed     : 100 Gbps\n"
                + "Oper State         : down\n"
                + "A:NOKIA-LIVE#show port description\n"
                + "Port Id        Description\n"
                + "1/1/1          To_DN-B\n"
                + "1/1/2          Reserved_For_Rehoming\n"
                + "1/1/c2/1       100-Gig Ethernet\n"
                + "10/1/15        10/100/Gig Ethernet SFP\n"
                + "10/2/c23/1     100-Gig Ethernet\n";
        List<LiveNodeHealthParser.PortSnapshot> ports
                = LiveNodeHealthParser.parsePorts("NOKIA-LIVE", "10.0.0.1",
                        "N-LLDP-Link_OPTIC", transcript);
        if (ports.size() != 7) {
            throw new AssertionError("Nokia compact live parser expected 7 ports, got " + ports.size());
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
        if (ports.get(0).crcTotal == null || ports.get(0).crcTotal.longValue() != 4L) {
            throw new AssertionError("Nokia FCS Errors counter was not parsed.");
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
        if (!"DOWN".equals(ports.get(3).portStatus) || !"100G".equals(ports.get(3).speed)) {
            throw new AssertionError("Nokia DOWN port did not fall back to Config Speed.");
        }
        if (!"DOWN".equals(ports.get(4).portStatus) || !"100M".equals(ports.get(4).speed)) {
            throw new AssertionError("Nokia management port state/speed was not parsed.");
        }
        if (!"DOWN".equals(ports.get(5).portStatus) || !"1G".equals(ports.get(5).speed)) {
            throw new AssertionError("Nokia configured 1G speed did not override the compact 10G optic hint.");
        }
        if (!"DOWN".equals(ports.get(6).portStatus) || !"100G".equals(ports.get(6).speed)) {
            throw new AssertionError("Nokia 100-Gig description did not correct a compact row without detail output.");
        }
        System.out.println("Nokia compact live transcript: ports=" + ports.size());
    }

    private static void verifyZteBriefAndCrcTranscript() {
        String transcript = "RN-LIVE#show interface brief\n"
                + "Interface               Attribute  Mode         BW    Admin Phy   Prot\n"
                + "gei-0/0/0/1             electric   Duplex/full  1G    up    up    up\n"
                + "To_ARUBA_MC\n"
                + "xgei-0/0/1/1            optical    Duplex/full  10G   up    down  down\n"
                + "Reserved\n"
                + "RN-LIVE#show interface gei-0/0/0/1\n"
                + "gei-0/0/0/1 is up, ifindex: 8317\n"
                + "  Description: To_ARUBA_MC\n"
                + "  BW 1 Gbit/s\n"
                + "  In_CRC_ERROR      4                    In_Unicasts        46293\n"
                + "  E_CRC_ERROR       N/A                  E_Unicasts         11355\n"
                + "RN-LIVE#\n";
        List<LiveNodeHealthParser.PortSnapshot> ports
                = LiveNodeHealthParser.parsePorts("RN-LIVE", "10.0.0.2",
                        "ZTE-LLDP-Link_OPTIC", transcript);
        if (ports.size() != 2) {
            throw new AssertionError("ZTE brief parser expected 2 ports, got " + ports.size());
        }
        LiveNodeHealthParser.PortSnapshot first = ports.get(0);
        if (!"UP".equals(first.portStatus) || !"1G".equals(first.speed)
                || !"To_ARUBA_MC".equals(first.description)) {
            throw new AssertionError("ZTE brief state, speed or description was not parsed.");
        }
        if (first.crcInput == null || first.crcInput.longValue() != 4L
                || first.crcOutput != null || first.crcTotal == null
                || first.crcTotal.longValue() != 4L) {
            throw new AssertionError("ZTE selected-port CRC counters were not parsed.");
        }
        if (!"DOWN".equals(ports.get(1).portStatus) || !"10G".equals(ports.get(1).speed)) {
            throw new AssertionError("ZTE brief physical state was not parsed.");
        }
        System.out.println("ZTE compact live transcript: ports=" + ports.size());
    }

    private static void verifyZtePortClassSpeedOverrides() {
        String transcript = "RN-LIVE#show interface brief\n"
                + "Interface               Attribute  Mode         BW    Admin Phy   Prot\n"
                + "gei-1/1/0/1             optical    Duplex/full  25G   up    up    up\n"
                + "xgei-1/1/0/30           optical    Duplex/full  25G   up    up    up\n"
                + "cgei-1/1/0/31           optical    Duplex/full  25G   up    up    up\n";
        List<LiveNodeHealthParser.PortSnapshot> ports
                = LiveNodeHealthParser.parsePorts("RN-LIVE", "10.0.0.4",
                        "ZTE-LLDP-Link_OPTIC", transcript);
        if (ports.size() != 3
                || !"1G".equals(ports.get(0).speed)
                || !"10G".equals(ports.get(1).speed)
                || !"100G".equals(ports.get(2).speed)) {
            throw new AssertionError("ZTE gei/xgei/cgei speed classification was not applied.");
        }
        System.out.println("ZTE port-class speeds: gei=1G, xgei=10G, cgei=100G");
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
                + "Input:\n"
                + "  Unicast: 20243107625791 packets, Multicast: 931125768 packets\n"
                + "  CRC: 238097811870 packets, Symbol: 230605413500 packets\n"
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
        if (ports.get(0).crcTotal == null
                || ports.get(0).crcTotal.longValue() != 238097811870L) {
            throw new AssertionError("Huawei input CRC counter was not parsed.");
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

    private static void verifyHuaweiCopperDistanceTranscript() {
        String transcript = "<LIVE>display interface description\n"
                + "Interface                      PHY     Protocol Description\n"
                + "GigabitEthernet7/0/3          up      up       To_RNC\n"
                + "<LIVE>display interface GigabitEthernet 7/0/3\n"
                + "GigabitEthernet7/0/3 current state : UP (ifindex: 465)\n"
                + "Line protocol current state : UP\n"
                + "Description: To_RNC\n"
                + "Port BW: 1G, Transceiver max BW: 1G, Transceiver Mode: Copper Mode\n"
                + "Wavelength: unknown, Transmission Distance: 100m\n"
                + "Input:\n"
                + "  CRC: 0 packets, Symbol: 0 packets\n"
                + "<LIVE>\n";
        List<LiveNodeHealthParser.PortSnapshot> ports
                = LiveNodeHealthParser.parsePorts("RN-LIVE", "10.0.0.3",
                        "HW-LLDP-Link_OPTIC", transcript);
        LiveNodeHealthParser.PortSnapshot target = null;
        for (LiveNodeHealthParser.PortSnapshot port : ports) {
            if ("GigabitEthernet7/0/3".equals(port.port)) {
                target = port;
                break;
            }
        }
        if (target == null || !"100m".equals(target.distance)) {
            throw new AssertionError("Huawei copper transmission distance was not parsed: "
                    + (target == null ? "missing port" : target.distance));
        }
        System.out.println("Huawei copper live distance: " + target.distance);
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
