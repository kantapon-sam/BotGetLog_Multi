package com.java.botgetlog.truecorp;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import sun.misc.Unsafe;

/** Real login reader with delayed AAA replies, bounded reads and fake reconnects. */
public final class SlowNodeLoginRegression {
    private static int checks;
    private static final String REJECT = "% Username or password error\r\n";
    private static final String CLOSED = "Username:Connection closed by foreign host.\r\nEnter IP address [press q/Q to quit]: ";

    public static void main(String[] args) throws Exception {
        String delayed = read(new DelayedStream(120, REJECT), 800);
        check(delayed.contains(REJECT.trim()) && CllsFallback.retryableRejection(delayed), "delayed rejection without next prompt retained");
        check(read(new DelayedStream(120, "QA-NODE#"), 800).equals("QA-NODE#"), "delayed successful AAA result");
        String partial = read(new DelayedStream(0, "partial banner"), 80);
        check(partial.contains("partial banner") && partial.endsWith("[TIMEOUT-READ]"), "partial bytes survive timeout");
        String closed = read(new DelayedStream(0, REJECT + CLOSED), 800);
        check(CllsFallback.reconnectableRejection(closed), "rejection and closing session retained together");
        for (String blocked : Arrays.asList(CLOSED, REJECT + "[TIMEOUT-READ]", REJECT + "connection reset",
                REJECT + "connection refused", REJECT + "broken pipe", REJECT + "account locked" + CLOSED,
                REJECT + "the IP has been blocked" + CLOSED)) {
            check(!CllsFallback.retryableRejection(blocked), "timeout/transport/lock never grants fallback: " + blocked);
        }
        String locked = read(new DelayedStream(0, "Authentication failed: user was locked\r\nUsername:"), 800);
        check(CllsFallback.authenticationBlocked(locked) && !CllsFallback.retryableRejection(locked), "reader retains lock detail after rejection prefix");
        Thread.currentThread().interrupt();
        check(read(new DelayedStream(0, ""), 800).contains("[INTERRUPTED]"), "stop interrupts login wait");
        Thread.interrupted();
        reconnectCases();
        liveProbeCases();
        liveWhoEnableCases();
        System.out.println("PASS SlowNodeLoginRegression checks=" + checks);
    }

    private static void liveWhoEnableCases() throws Exception {
        Class<?> type = Class.forName("com.java.botgetlog.truecorp.Telnet_Multi$CredentialProbe");
        Constructor<?> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        Method enable = type.getDeclaredMethod("ensureZtePrivilegedMode"); enable.setAccessible(true);
        String property = "botgetlog.enable.settingsDir", previous = System.getProperty(property);
        java.nio.file.Path settings = java.nio.file.Files.createTempDirectory("who-enable-regression-");
        System.setProperty(property, settings.toString());
        try {
            for (boolean who : new boolean[]{false, true}) {
                Object probe = constructor.newInstance(); ByteArrayOutputStream sent = new ByteArrayOutputStream();
                Map<String,Object> fields = new HashMap<>(); fields.put("lastPromptToken", "QA-NODE#");
                fields.put("forceZteEnableAfterLogin", who); fields.put("in", new ByteArrayInputStream("QA-NODE#".getBytes(StandardCharsets.UTF_8)));
                fields.put("out", new PrintStream(sent));
                for (Map.Entry<String,Object> entry : fields.entrySet()) { Field f = type.getDeclaredField(entry.getKey()); f.setAccessible(true); f.set(probe,entry.getValue()); }
                check((Boolean) enable.invoke(probe), "personal ZTE reaches privilege");
                check(sent.toString("UTF-8").replace("\r", "").equals(who ? "enable\n" : ""), "who explicitly enables hash prompt; primary keeps existing privilege");
                ((AutoCloseable) probe).close();
            }
        } finally {
            if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
            try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(settings)) {
                for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) paths.sorted(Comparator.reverseOrder())::iterator) java.nio.file.Files.delete(path);
            }
        }
    }

    private static void liveProbeCases() throws Exception {
        Class<?> type = Class.forName("com.java.botgetlog.truecorp.Telnet_Multi$CredentialProbe");
        Constructor<?> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        Method wait = type.getDeclaredMethod("waitForPostPasswordPrompt", String.class, String.class); wait.setAccessible(true);
        for (String text : Arrays.asList("Error: Username or password error\r\nUsername:", "QA-NODE#")) {
            Object probe = constructor.newInstance();
            Field input = type.getDeclaredField("in"); input.setAccessible(true); input.set(probe, new DelayedStream(120, text));
            ByteArrayOutputStream sent = new ByteArrayOutputStream();
            Field output = type.getDeclaredField("out"); output.setAccessible(true); output.set(probe, new PrintStream(sent));
            String response = (String) wait.invoke(probe, "personal", "secret");
            check(response.equals(text), "TrueLogs retains delayed complete response");
            check(sent.size() == 0, "TrueLogs never resubmits rejected personal credentials");
            Field prompt = type.getDeclaredField("lastPromptToken"); prompt.setAccessible(true);
            if (text.endsWith("#")) check("QA-NODE#".equals(prompt.get(probe)), "TrueLogs retains prompt for enable and collection");
            ((AutoCloseable) probe).close();
        }
    }

    private static void reconnectCases() throws Exception {
        Method login = Telnet_Multi.class.getDeclaredMethod("loginCllsBackup", CllsFallback.Credential.class, String.class);
        login.setAccessible(true);
        Method verify = Telnet_Multi.class.getDeclaredMethod("verifiedFallbackNodePrompt", String.class, String.class, String.class);
        verify.setAccessible(true);
        for (String ready : Arrays.asList("Username:", "Enter IP address [press q/Q to quit]: ", "QA-NODE#", "[TIMEOUT-READ]", "Account locked\r\nUsername:")) {
            FakeCollector c = fixture(); c.ready = ready;
            set(c, "in", new ByteArrayInputStream("Password:\r\nQA-NODE#".getBytes(StandardCharsets.UTF_8)));
            ByteArrayOutputStream sent = new ByteArrayOutputStream(); set(c, "out", new PrintStream(sent, true, "UTF-8"));
            String answer = (String) login.invoke(c, new CllsFallback.Credential(1, "backup-who", "fake-secret"), REJECT + CLOSED);
            check(c.reconnects == 1, "at most one reconnect for a backup slot");
            if (ready.equals("Username:")) {
                check(sent.toString("UTF-8").replace("\r", "").equals("backup-who\nfake-secret\n"), "fresh node prompt gates one submission");
                check((Boolean) verify.invoke(c, answer, "QA-NODE", "ZTE-LLDP-Link_OPTIC"), "same node verified after reconnect");
                check(!(Boolean) verify.invoke(c, answer, "OTHER-NODE", "ZTE-LLDP-Link_OPTIC"), "different node rejected");
            } else check(sent.size() == 0, "no credentials on gateway/auto-login/timeout/lock");
        }
        FakeCollector c = fixture(); c.ready = "Username:";
        ByteArrayOutputStream sent = new ByteArrayOutputStream(); set(c, "out", new PrintStream(sent));
        login.invoke(c, new CllsFallback.Credential(1, "backup", "secret"), CLOSED);
        check(c.reconnects == 0 && sent.size() == 0, "closed connection without rejection cannot reconnect");
        List<CllsFallback.Credential> backups = Arrays.asList(new CllsFallback.Credential(1, "who", "a"),
                new CllsFallback.Credential(2, "who", "a"), new CllsFallback.Credential(3, "other", "b"));
        CllsFallback.Result result = CllsFallback.attempt(REJECT + CLOSED, "primary", "p", backups,
                (credential, rejected) -> REJECT + CLOSED, text -> false);
        check(result.attempts == 2 && result.successful == null, "closed rejections remain bounded and duplicate accounts skipped");
    }

    private static final class FakeCollector extends Telnet_Multi {
        int reconnects; String ready;
        private FakeCollector() { super("", "", "", "", "", "", "", "", 0, "", ""); }
        @Override String reconnectForCllsBackup() { reconnects++; return ready; }
        @Override public void logwork(String ignored) { }
    }
    private static FakeCollector fixture() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
        return (FakeCollector) ((Unsafe) f.get(null)).allocateInstance(FakeCollector.class);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field f = Telnet_Multi.class.getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    private static String read(InputStream stream, int timeout) throws Exception {
        Method m = Telnet_Multi.class.getDeclaredMethod("readNodeLoginResponse", InputStream.class, PrintStream.class, int.class, String[].class);
        m.setAccessible(true);
        return (String) m.invoke(null, stream, new PrintStream(new ByteArrayOutputStream()), timeout, new String[]{">", "#"});
    }
    private static final class DelayedStream extends InputStream {
        final long ready; final byte[] bytes; int offset;
        DelayedStream(int delay, String text) { ready = System.nanoTime() + delay * 1000000L; bytes = text.getBytes(StandardCharsets.UTF_8); }
        public int available() { return System.nanoTime() >= ready ? bytes.length - offset : 0; }
        public int read() { return available() > 0 ? bytes[offset++] & 255 : -1; }
    }
    private static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
}
