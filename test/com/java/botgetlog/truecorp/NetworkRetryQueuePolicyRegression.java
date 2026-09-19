package com.java.botgetlog.truecorp;

public final class NetworkRetryQueuePolicyRegression {

    private NetworkRetryQueuePolicyRegression() {
    }

    public static void main(String[] args) {
        assertEquals(3, BotGetLog_TrueCorp.networkRerunRoundLimit());
        assertTrue(Telnet_Multi.isRetryableNetworkFailureReason(
                "[Connection failed - command timeout]"));
        assertTrue(Telnet_Multi.isRetryableNetworkFailureReason(
                "[Connection failed - remote closed]"));
        assertTrue(Telnet_Multi.isRetryableNetworkFailureReason(
                "[No login prompt - connection timed out]"));
        assertFalse(Telnet_Multi.isRetryableNetworkFailureReason(
                "[Auth failed - username or password rejected]"));
        assertFalse(Telnet_Multi.isRetryableNetworkFailureReason(
                "[Wrong vendor detected]"));
        assertFalse(Telnet_Multi.isRetryableNetworkFailureReason(
                "[CmdSet rejected - device reported command error]"));

        BotGetLog_TrueCorp.successCount.set(4);
        BotGetLog_TrueCorp.failCount.set(3);
        BotGetLog_TrueCorp.networkFailCount.set(2);
        BotGetLog_TrueCorp.resetRunCounters();
        assertEquals(0, BotGetLog_TrueCorp.successCount.get());
        assertEquals(0, BotGetLog_TrueCorp.failCount.get());
        assertEquals(0, BotGetLog_TrueCorp.networkFailCount.get());
        System.out.println("PASS NetworkRetryQueuePolicyRegression TRUE");
    }

    private static void assertTrue(boolean value) {
        if (!value) throw new AssertionError("Expected true");
    }

    private static void assertFalse(boolean value) {
        if (value) throw new AssertionError("Expected false");
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
