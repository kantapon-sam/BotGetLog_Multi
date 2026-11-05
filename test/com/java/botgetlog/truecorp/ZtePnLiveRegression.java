package com.java.botgetlog.truecorp;

import com.java.tools.linkoptical.LiveNodeHealthParser;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** PN live polling must avoid the full chassis dump and retain selected-port detail. */
public final class ZtePnLiveRegression {
    private static int checks;

    public static void main(String[] args) {
        List<String> compact = Arrays.asList("terminal length 0", "show processor",
                "show opt brief", "show interface brief");
        for (String name : Arrays.asList("PN-TEST", "PN1-TEST0001", "PN2-TEST0002")) {
            equal(compact, commands(name, "PN", "all", null), "PN inventory type");
            equal(compact, commands(name, "", "all", null), "PN name fallback");
        }
        equal(compact, commands("test-node", " pn ", "all", null), "normalized PN type");
        equal(compact, commands("pn1-test", "", "all", null), "normalized PN name");
        equal(compact, commands("RN-TEST", "RN", "all", null), "RN profile retained");
        equal(compact, commands("AGN-TEST", "AGN", "all", null), "AGN profile retained");
        for (String type : Arrays.asList("DN", "CPE")) {
            List<String> other = commands(type + "-TEST", type, "all", null);
            require(other.contains("show interface") && !other.contains("show interface brief"),
                    "unrelated node profile changed");
        }
        equal(Arrays.asList("terminal length 0", "show processor"),
                commands("PN1-TEST", "PN", "cpu", null), "CPU-only commands");
        equal(Arrays.asList("terminal length 0", "show opt brief"),
                commands("PN1-TEST", "PN", "optical", null), "optical-only commands");
        List<String> ports = Arrays.asList("xgei-0/0/0/1", "xgei-0/0/0/2",
                "xgei-0/0/0/3", "xgei-0/0/0/4", "xgei-0/0/0/5", "xgei-0/0/0/6");
        List<String> selected = commands("PN1-TEST", "PN", "all", ports);
        equal(compact, selected.subList(0, compact.size()), "compact inventory before details");
        require(selected.size() == compact.size() + 5, "selected detail limit");
        for (int i = 0; i < 5; i++) {
            equal("show interface " + ports.get(i), selected.get(compact.size() + i),
                    "only selected port receives detail");
        }
        require(!selected.contains("show interface"), "full chassis dump must never be added");

        String transcript = "PN1-TEST#show interface brief\n"
                + "xgei-0/0/0/1 1 1500 10G up up up\nTo-Core\n"
                + "xgei-0/0/0/2 2 1500 10G up down down\nSpare\n"
                + "PN1-TEST#show interface xgei-0/0/0/1\n"
                + "xgei-0/0/0/1 is up, ifindex: 1\nDescription: To-Core\n"
                + "BW 10000 Mbit/s\nIn_CRC_ERROR 7\nE_CRC_ERROR 2\nPN1-TEST#\n";
        List<LiveNodeHealthParser.PortSnapshot> parsed = LiveNodeHealthParser.parsePorts(
                "PN1-TEST", "192.0.2.1", "ZTE-LLDP-Link_OPTIC", transcript);
        require(parsed.size() == 2, "brief inventory preserved when detail is selected");
        LiveNodeHealthParser.PortSnapshot first = parsed.get(0), second = parsed.get(1);
        equal("UP", first.portStatus, "selected port state");
        equal(Long.valueOf(9), first.crcTotal, "selected detail CRC");
        equal("DOWN", second.portStatus, "unselected brief port state");
        require(second.crcTotal == null, "uncollected CRC must remain unknown");
        System.out.println("PASS ZtePnLiveRegression " + checks + " checks");
    }

    private static List<String> commands(String node, String type, String metrics, List<String> ports) {
        return TrueLiveNodeProbeCli.commandsFor("ZTE-LLDP-Link_OPTIC", metrics, node, type,
                ports == null ? Collections.<String>emptyList() : ports);
    }

    private static void equal(Object expected, Object actual, String message) {
        require(expected.equals(actual), message + ": " + actual);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
