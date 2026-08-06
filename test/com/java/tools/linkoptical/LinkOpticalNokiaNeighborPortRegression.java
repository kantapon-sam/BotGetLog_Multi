package com.java.tools.linkoptical;

import java.util.Arrays;
import java.util.List;

public final class LinkOpticalNokiaNeighborPortRegression {

    private LinkOpticalNokiaNeighborPortRegression() {
    }

    public static void main(String[] args) {
        verifyWrappedHuaweiPortId();
        verifySingleLineNokiaPortId();
        verifyHexDecodeFallback();
        verifyDescriptionIsNotUsedAsPortId();
        verifyWrappedPortDescriptionToken();
        verifyWrappedPortDescriptionWords();
        System.out.println("PASS LinkOpticalNokiaNeighborPortRegression");
    }

    private static void verifyWrappedHuaweiPortId() {
        List<String> lines = Arrays.asList(
                "Port Id               : 47:69:67:61:62:69:74:45:74:68:65:72:6E:65:74:30:2F:32:",
                "                        2F:31",
                "                        \"GigabitEthernet0/2/1\"",
                "Port Description      : To_DN1-RYG4002_NSR12_10G_2/1/8_10.167.33.42",
                "System Name           : CPE-CBR7469");

        assertEquals("GigabitEthernet0/2/1", Check_Link_Optical.extractNokiaPortId(lines, 0));
    }

    private static void verifySingleLineNokiaPortId() {
        List<String> lines = Arrays.asList(
                "Port Id               : 31:2F:31:2F:32:34",
                "                        \"1/1/24\"",
                "Port Description      : 1/1/24, 1-Gig/10-Gig Ethernet",
                "System Name           : CPE-RYG7132");

        assertEquals("1/1/24", Check_Link_Optical.extractNokiaPortId(lines, 0));
    }

    private static void verifyHexDecodeFallback() {
        List<String> lines = Arrays.asList(
                "Port Id               : 78:67:65:69:2D:31:2F:31:2F:30:2F:33:30",
                "Port Description      : xgei-1/1/0/30(optical)");

        assertEquals("xgei-1/1/0/30", Check_Link_Optical.extractNokiaPortId(lines, 0));
    }

    private static void verifyDescriptionIsNotUsedAsPortId() {
        List<String> lines = Arrays.asList(
                "Port Id               :",
                "Port Description      : To_DN1-RYG4002_NSR12_10G_2/1/8_10.167.33.42",
                "System Name           : CPE-CBR7469");

        String portId = Check_Link_Optical.extractNokiaPortId(lines, 0);
        assertEquals("", portId);
        assertEquals("", Check_Link_Optical.cleanNokiaNeighborPort(
                portId, "To_DN1-RYG4002_NSR12_10G_2/1/8_10.167.33.42"));
    }

    private static void verifyWrappedPortDescriptionToken() {
        StringBuilder ipDescription = new StringBuilder(
                "To_RN-CBR0208-4_XRS20E_100G_10/1/c4/1_10.163.0.");
        Check_Link_Optical.appendNokiaWrappedText(ipDescription, "134_TLR05466");
        assertEquals(
                "To_RN-CBR0208-4_XRS20E_100G_10/1/c4/1_10.163.0.134_TLR05466",
                ipDescription.toString());

        StringBuilder nodeDescription = new StringBuilder("To_PN-RYG0012-");
        Check_Link_Optical.appendNokiaWrappedText(
                nodeDescription, "2_SR12E_100G_6/1/1_10.167.33.2_TLR05466");
        assertEquals(
                "To_PN-RYG0012-2_SR12E_100G_6/1/1_10.167.33.2_TLR05466",
                nodeDescription.toString());
    }

    private static void verifyWrappedPortDescriptionWords() {
        StringBuilder description = new StringBuilder("Customer uplink to");
        Check_Link_Optical.appendNokiaWrappedText(description, "RN site");
        assertEquals("Customer uplink to RN site", description.toString());
    }

    private static void assertEquals(String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected [" + expected + "] but was [" + actual + "]");
        }
    }
}
