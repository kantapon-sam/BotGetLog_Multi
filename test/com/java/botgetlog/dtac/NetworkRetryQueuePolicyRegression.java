package com.java.botgetlog.dtac;

public final class NetworkRetryQueuePolicyRegression {

    private NetworkRetryQueuePolicyRegression() {
    }

    public static void main(String[] args) {
        assertEquals(3, BotGetLog_DTAC.networkRerunRoundLimit());
        assertTrue(BotGetLog_DTAC.isNetworkRetryEligible(false, "NETWORK_FAILED"));
        assertFalse(BotGetLog_DTAC.isNetworkRetryEligible(true, "NETWORK_FAILED"));
        assertFalse(BotGetLog_DTAC.isNetworkRetryEligible(false, "AUTH_FAILED"));
        assertFalse(BotGetLog_DTAC.isNetworkRetryEligible(false, "COMMAND_INCOMPLETE"));
        assertFalse(StopProgram.shouldShowFinished(1744, 1744, false, false));
        assertTrue(StopProgram.shouldShowFinished(1744, 1744, true, false));
        assertFalse(StopProgram.shouldShowFinished(1744, 1743, true, false));
        assertFalse(StopProgram.shouldShowFinished(1744, 1744, true, true));
        System.out.println("PASS NetworkRetryQueuePolicyRegression DTAC");
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
