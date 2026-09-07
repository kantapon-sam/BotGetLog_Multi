package com.java.botgetlog.truecorp;

import com.java.tools.linkoptical.HuaweiLivePort;
import com.java.tools.linkoptical.LiveNodeHealthParser;
import com.java.tools.linkoptical.LiveNodeHealthParser.PortSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.*;

/** Offline-only regression: never logs in or sends commands to a network node. */
public final class HuaweiDualRateLiveRegression {
    public static void main(String[] args) throws Exception {
        inventory();
        detailsAndBoundaries();
        selectionsAndSafety();
        if (args.length == 1) savedTranscript(args[0]);
        System.out.println("Huawei dual-rate live regression: PASS");
    }

    private static List<PortSnapshot> parse(String text) {
        return LiveNodeHealthParser.parsePorts("AGN-LIVE", "10.0.0.1", "HW", text);
    }

    private static PortSnapshot port(List<PortSnapshot> rows, String name) {
        PortSnapshot match = null;
        for (PortSnapshot row : rows) if (name.equals(row.port)) {
            check(match == null, "duplicate row: " + name);
            match = row;
        }
        check(match != null, "missing port: " + name);
        return match;
    }

    private static void inventory() {
        StringBuilder text = new StringBuilder("<LIVE>display interface description\n");
        List<String> names = new ArrayList<String>();
        for (int slot : new int[]{6, 10, 11}) {
            for (int sub = 0; sub < (slot == 11 ? 1 : 2); sub++) {
                for (int p : new int[]{0, 2, 4, 6}) {
                    String name = "50|100GE" + slot + "/" + sub + "/" + p;
                    names.add(name);
                    text.append(name).append("(100G) ")
                            .append(p < 4 ? "up up Uplink\n" : "*down down Reserved\n");
                }
            }
        }
        text.append("GE1/1/0(10G) down down Existing10G\n")
                .append("100GE2/0/0 up up Existing100G\n")
                .append("50|100GE12/0/0(50G) up up FiftyGigMode\n")
                .append("50|100GE12/0/2 down down UnknownMode\n")
                .append("50|100GE6/0/0.123 up up LogicalSubinterface\n");
        List<PortSnapshot> rows = parse(text.toString());
        check(rows.size() == 24, "inventory size: " + rows.size());
        for (String name : names) check("100G".equals(port(rows, name).speed), "speed: " + name);
        check("UP".equals(port(rows, names.get(0)).portStatus), "up status");
        check("DOWN".equals(port(rows, names.get(2)).portStatus), "admin-down status");
        check("10G".equals(port(rows, "GE1/1/0").speed), "existing GE 10G");
        check("50G".equals(port(rows, "50|100GE12/0/0").speed), "dual-rate 50G hint");
        check(port(rows, "50|100GE12/0/2").speed.isEmpty(), "do not guess dual-rate speed");
        List<String> active = Telnet_Multi.selectHuaweiActivePorts(text.toString(), 64);
        check(active.size() == 12, "active physical count: " + active);
        check(active.contains("50|100GE6/0/0") && !active.contains("50|100GE6/0/4"), "active selection");
        check(Telnet_Multi.selectHuaweiActivePorts(text.toString(), 5).size() == 5, "selection ceiling");
        System.out.println("Inventory: all 20 chassis dual-rate ports present; 10G/100GE/50G preserved");
    }

    private static void detailsAndBoundaries() {
        String text = "<LIVE>display interface description\n"
                + "50|100GE6/0/0(100G) up up First\n"
                + "50|100GE6/0/4(50G) *down down Second\n"
                + "<LIVE>display interface 50|100GE 6/0/0\n"
                + "50|100GE6/0/0 current state : UP (ifindex: 1143)\n"
                + "Description: First\nPort BW: 100G, Transceiver max BW: 100G\n"
                + "Connector Type: LC, Transmission Distance: 10km\nWaveLength: 1295.56nm\n"
                + "Rx Warning range: [-10.599, 4.500]dBm\n"
                + "Rx0 Power: -1.00dBm, Tx0 Power: 1.00dBm\n"
                + "Rx1 Power: -2.00dBm, Tx1 Power: 2.00dBm\n"
                + "Rx2 Power: -3.00dBm, Tx2 Power: 3.00dBm\n"
                + "Rx3 Power: -4.00dBm, Tx3 Power: 4.00dBm\nCRC: 42 packets\n"
                + "<LIVE>display interface 50|100GE 6/0/4\n"
                + "50|100GE6/0/4 current state : Administratively DOWN (ifindex: 1147)\n"
                + "Description: Second\nPort BW: 50G, Transceiver max BW: 100G\nCRC: 7 packets\n"
                + "50|100GE6/0/4.123 current state : UP\n"
                + "Description: MustNotOverwritePhysical\nPort BW: 1G\nCRC: 9999 packets\n"
                + "Rx0 Power: 90.00dBm, Tx0 Power: 90.00dBm\n"
                + "Rx1 Power: 90.00dBm, Tx1 Power: 90.00dBm\n<LIVE>\n";
        List<PortSnapshot> rows = parse(text);
        check(rows.size() == 2, "summary/detail merged once");
        PortSnapshot first = port(rows, "50|100GE6/0/0");
        PortSnapshot second = port(rows, "50|100GE6/0/4");
        check("First".equals(first.description) && "100G".equals(first.speed), "first identity/speed");
        check(first.hasOpticalData && Math.abs(first.rxPowerDbm + 2.5) < 0.001
                && Math.abs(first.txPowerDbm - 2.5) < 0.001, "all four optical lanes averaged");
        check(Long.valueOf(42).equals(first.crcTotal), "first CRC isolated");
        check("DOWN".equals(second.portStatus) && "Second".equals(second.description)
                && "50G".equals(second.speed) && Long.valueOf(7).equals(second.crcTotal),
                "admin-down detail separated from preceding and logical interfaces");
        check(second.rxPowerDbm == null || Math.abs(second.rxPowerDbm) < 0.001, "logical lanes excluded");
        System.out.println("Details: four optical lanes, CRC, admin-down and logical boundaries verified");
    }

    private static void selectionsAndSafety() {
        String good = "50|100GE6/0/0";
        String[] bad = {"50|100GE6/0/0|include", "50|100GE6/0/0 | include x", "GE1/1/0|x",
            "50|100GE6/0/0;reboot", "50|100GE6/0/0\nreboot", "50|100GE6/0/0.123",
            "50||100GE6/0/0", "50|100GE6/0", "50|100GE6/0/0(100G)"};
        for (String value : bad) {
            check(!HuaweiLivePort.isDualRatePort(value), "invalid dual-rate name accepted");
            check(TrueLiveNodeProbeCli.parsePortList(value, "HW").isEmpty(), "CLI injection accepted");
            List<String> commands = TrueLiveNodeProbeCli.commandsFor("HW", "all", "AGN-LIVE", "AGN",
                    Collections.singletonList(value));
            for (String command : commands) check(!command.contains(value), "command allow-list bypass");
        }
        check(TrueLiveNodeProbeCli.parsePortList(good, "HW").equals(Collections.singletonList(good)), "CLI --ports");
        for (String vendor : new String[]{"N", "ZTE", ""}) {
            check(TrueLiveNodeProbeCli.parsePortList(good, vendor).isEmpty(), "foreign vendor pipe");
        }
        List<String> commands = TrueLiveNodeProbeCli.commandsFor("HW", "all", "AGN-LIVE", "AGN",
                Arrays.asList(good, "GE1/1/0"));
        check(commands.contains("display interface 50|100GE 6/0/0"), "typed dual-rate detail command");
        check(commands.contains("display interface GE1/1/0"), "existing detail command retained");
        check("50|100GE 6/0/0".equals(HuaweiLivePort.commandArgument(good)), "actual interface type retained");
        System.out.println("Selections: strict pipe validation and read-only detail commands verified");
    }

    private static void savedTranscript(String path) throws Exception {
        String raw = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        Set<String> names = new LinkedHashSet<String>();
        Matcher headers = Pattern.compile("(?m)^(50\\|100GE\\d+(?:/\\d+){2,4}) current state").matcher(raw);
        while (headers.find()) names.add(headers.group(1));
        check(!names.isEmpty(), "saved transcript must contain dual-rate ports");
        List<PortSnapshot> rows = parse(raw);
        for (String name : names) port(rows, name);
        System.out.println("Saved node transcript: " + names.size() + " dual-rate ports retained, total=" + rows.size());
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
