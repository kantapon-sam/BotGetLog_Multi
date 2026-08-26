package com.java.botgetlog.truecorp;

import java.util.LinkedHashSet;
import java.util.List;

/** Manual regression checks for Nokia live speed-detail selection. */
public final class TelnetMultiLiveSpeedRegression {

    private TelnetMultiLiveSpeedRegression() {
    }

    public static void main(String[] args) {
        String summary = "Port          Admin Link Port    Cfg  Oper LAG/ Port Port Port   C/QS/S/XFP/\n"
                + "1/1/c2/1      Up    No   Down    9212 9212    - netw null xcme\n"
                + "1/1/c3/1      Up    No   Down    9212 9212    - netw null xgige\n"
                + "1/1/c4/1      Up    No   Down    9212 9212    - netw null c100g\n"
                + "1/1/c5/1      Up    No   Down    9212 9212    - netw null xcme\n"
                + "1/1/c6/1      Up    No   Down    9212 9212    - netw null gige\n";
        String descriptions = "Port Id        Description\n"
                + "1/1/c2/1       100-Gig Ethernet\n"
                + "1/1/c3/1       10-Gig Ethernet\n"
                + "1/1/c4/1       100-Gig Ethernet\n"
                + "1/1/c5/1       10/100/Gig Ethernet SFP\n"
                + "1/1/c6/1       25-Gig Ethernet\n";

        List<String> selected = Telnet_Multi.selectNokiaSpeedVerificationPorts(
                summary, descriptions, new LinkedHashSet<String>(), 64);
        if (selected.size() != 2
                || !"1/1/c2/1".equals(selected.get(0))
                || !"1/1/c6/1".equals(selected.get(1))) {
            throw new AssertionError("Unexpected Nokia speed verification ports: " + selected);
        }

        LinkedHashSet<String> expanded = new LinkedHashSet<String>();
        expanded.add("1/1/c2/1");
        selected = Telnet_Multi.selectNokiaSpeedVerificationPorts(
                summary, descriptions, expanded, 1);
        if (selected.size() != 1 || !"1/1/c6/1".equals(selected.get(0))) {
            throw new AssertionError("Expanded Nokia ports were not excluded: " + selected);
        }
        System.out.println("Nokia live speed verification selection: PASS");
    }
}
