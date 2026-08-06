package com.java.tools.linkoptical;

public final class LinkOpticalFreePortRegression {

    private LinkOpticalFreePortRegression() {
    }

    public static void main(String[] args) {
        verifyNokiaConnectorNormalization();
        verifyNokiaConnectorBandwidth();
        verifyNokiaConnectorUsage();
        verifyNokiaConnectorIsCountedOnce();
        System.out.println("PASS LinkOpticalFreePortRegression");
    }

    private static void verifyNokiaConnectorNormalization() {
        assertEquals("8/1/c1", Link_Optical.normalizeFreePortInterface("8/1/c1/1"));
        assertEquals("8/1/c1", Link_Optical.normalizeFreePortInterface("8/1/c1/4"));
        assertEquals("1/1/c33", Link_Optical.normalizeFreePortInterface("1/1/C33/1"));
        assertEquals("1/1/1", Link_Optical.normalizeFreePortInterface("'1/1/1"));
    }

    private static void verifyNokiaConnectorBandwidth() {
        assertEquals("100G", Link_Optical.normalizeBW("8/1/c1/1", ""));
        assertEquals("100G", Link_Optical.normalizeBW("8/1/c1/2", "10 Gbps"));
        assertEquals("100G", Link_Optical.normalizeBW("'8/1/C1/3", "1G"));
        assertEquals("10G", Link_Optical.normalizeBW("1/1/1", "10 Gbps"));
    }

    private static void verifyNokiaConnectorUsage() {
        assertEquals(true, Link_Optical.isNokiaConnectorUsed("up", "QSFP28 Connector"));
        assertEquals(false, Link_Optical.isNokiaConnectorUsed("down", "QSFP28 Connector"));
        assertEquals(false, Link_Optical.isNokiaConnectorUsed("down", ""));
        assertEquals(true, Link_Optical.isNokiaConnectorUsed("down", "To_DN-UDT0617-1"));
    }

    private static void verifyNokiaConnectorIsCountedOnce() {
        Link_Optical.PortSummary summary = new Link_Optical.PortSummary();

        Link_Optical.recordFreePort(summary, "8/1/c1/1", "10G",
                false, false, false, false);
        Link_Optical.recordFreePort(summary, "8/1/c1/2", "1G",
                true, false, false, true);
        Link_Optical.recordFreePort(summary, "8/1/c1", "",
                false, true, false, false);
        Link_Optical.recordFreePort(summary, "8/1/c2/1", "10G",
                false, false, false, false);
        Link_Optical.recordFreePort(summary, "1/1/1", "10G",
                false, false, false, false);

        Link_Optical.flushNokiaConnectorPorts(summary);

        assertEquals(2, summary.total100G);
        assertEquals(1, summary.used100G);
        assertEquals(1, summary.total100G - summary.used100G);
        assertEquals(1, summary.reservedForRehoming);
        assertEquals(0, summary.reserved100G);
        assertEquals(1, summary.total10G);
        assertEquals(0, summary.used10G);

        Link_Optical.flushNokiaConnectorPorts(summary);
        assertEquals(2, summary.total100G);
    }

    private static void assertEquals(String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected [" + expected + "] but was [" + actual + "]");
        }
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("Expected [" + expected + "] but was [" + actual + "]");
        }
    }

    private static void assertEquals(boolean expected, boolean actual) {
        if (expected != actual) {
            throw new AssertionError("Expected [" + expected + "] but was [" + actual + "]");
        }
    }
}
