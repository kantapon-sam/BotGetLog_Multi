package com.java.botgetlog.truecorp;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import sun.misc.Unsafe;

/** Offline authentication exchanges and optional encrypted CLLS account contract. */
public final class CllsFallbackRegression {
    private static int checks;
    private static final String REJECT = "Authentication failed\r\nUsername:";
    private static final String PROMPT = "<QA-NODE>";
    private static final List<CllsFallback.Credential> BACKUPS = Arrays.asList(
            new CllsFallback.Credential(1, "backup-a", "secret-a"),
            new CllsFallback.Credential(2, "backup-b", "secret-b"),
            new CllsFallback.Credential(5, "backup-e", "secret-e"));

    public static void main(String[] args) throws Exception {
        policyCases();
        streamCases();
        storeCases();
        eventCases();
        System.out.println("PASS CllsFallbackRegression checks=" + checks);
    }

    private static void policyCases() {
        List<Integer> tried = new ArrayList<>();
        CllsFallback.Result result = CllsFallback.attempt(REJECT, "clls", "primary", BACKUPS,
                (credential, previous) -> { tried.add(credential.slot); return credential.slot == 2 ? PROMPT : REJECT; },
                PROMPT::equals);
        equal(Arrays.asList(1, 2), tried, "stop after first verified success");
        equal(2, result.successful.slot, "successful slot");
        for (String stopped : Arrays.asList(PROMPT, "Username:", "Password:", "[TIMEOUT-READ]", "Connection closed by foreign host",
                "Authentication failed: user was locked", "Authentication failed: the IP has been blocked",
                "too many authentication failures", "Gateway login failed", "Login incorrect: account expired")) {
            CllsFallback.Result stop = CllsFallback.attempt(stopped, "clls", "primary", BACKUPS,
                    (credential, previous) -> { throw new AssertionError("unexpected backup on " + stopped); }, PROMPT::equals);
            equal(0, stop.attempts, "no fallback without retryable explicit node rejection");
        }
        List<CllsFallback.Credential> duplicate = Arrays.asList(
                new CllsFallback.Credential(1, "clls", "primary"), BACKUPS.get(0),
                new CllsFallback.Credential(3, "backup-a", "secret-a"),
                new CllsFallback.Credential(4, "partial", ""), BACKUPS.get(2));
        tried.clear();
        result = CllsFallback.attempt(REJECT, "clls", "primary", duplicate,
                (credential, previous) -> { tried.add(credential.slot); return REJECT; }, PROMPT::equals);
        equal(Arrays.asList(1, 5), tried, "skip duplicate primary, duplicate backup, empty pair");
        equal(null, result.successful, "all rejected produces no success");
        result = CllsFallback.attempt(REJECT, "clls", "primary", BACKUPS,
                (credential, previous) -> "Authentication failed: user was locked", PROMPT::equals);
        equal(1, result.attempts, "hard block stops remaining backups");
        result = CllsFallback.attempt(REJECT, "clls", "primary", BACKUPS,
                (credential, previous) -> "<OTHER-NODE>", PROMPT::equals);
        equal(null, result.successful, "wrong node prompt never becomes success");
        equal(1, result.attempts, "wrong prompt cannot trigger another credential");
        equal(0, CllsFallback.attempt(REJECT, "clls", "primary", Collections.emptyList(),
                (credential, previous) -> PROMPT, PROMPT::equals).attempts, "backups optional");
        String sam = "Please Enter IP address\r\n192.0.2.1\r\nEnter IP address [press q/Q to quit]: 192.0.2.1\r\n"
                + "Trying to connect to 192.0.2.1 ,please wait ...\r\nUsername:";
        check(CllsFallback.retryableRejection(sam + "clls\r\nPassword:\r\n% Username or password error\r\nUsername:"),
                "historical SAM menu does not mask current ZTE rejection");
        check(!CllsFallback.hardStop(sam + "clls\r\nPassword:\r\nQA-NODE#"),
                "historical SAM menu does not reject primary success");
        for (String ending : Arrays.asList("Enter IP address [press q/Q to quit]: ", "Please Enter IP address\r\n",
                "\u001b[32mEnter IP address [press q/Q to quit]:\u001b[0m\r\n  ", "Connection closed by foreign host",
                "[TIMEOUT-READ]", "Account locked")) {
            check(CllsFallback.hardStop(sam + "\r\n" + REJECT + "\r\n" + ending),
                    "current gateway/transport/lock still stops after historical rejection");
        }
    }

    private static void streamCases() throws Exception {
        Object[] fixture = collector("Password:\r\n" + PROMPT);
        Method login = Telnet_Multi.class.getDeclaredMethod("loginCllsBackup", CllsFallback.Credential.class, String.class);
        login.setAccessible(true);
        String response = (String) login.invoke(fixture[0], BACKUPS.get(0), REJECT);
        equal(PROMPT, response.trim(), "actual reader receives authenticated prompt");
        equal("backup-a\nsecret-a\n", fixture[1].toString().replace("\r", ""), "one submission per credential");
        Method verify = Telnet_Multi.class.getDeclaredMethod("verifiedFallbackNodePrompt", String.class, String.class, String.class);
        verify.setAccessible(true);
        equal(true, verify.invoke(fixture[0], response, "QA-NODE", "HW-LLDP-Link_OPTIC"), "verified node identity");
        equal(false, verify.invoke(fixture[0], "<OTHER-NODE>", "QA-NODE", "HW-LLDP-Link_OPTIC"), "reject other node");
        equal(false, verify.invoke(fixture[0], "Username:", "QA-NODE", "HW-LLDP-Link_OPTIC"), "prompt send alone insufficient");
        for (String[] vendor : new String[][]{
                {"N-LLDP-Link_OPTIC", "QA-NODE", "*A:QA-NODE#"},
                {"ZTE-LLDP-Link_OPTIC", "QA-NODE", "QA-NODE>"},
                {"J-LLDP-Link_OPTIC", "QA-NODE-re0", "backup@QA-NODE-re1>"},
                {"J-ARP", "QA-NODE-re0", "backup@QA-NODE-re0>"}}) {
            Object[] v = collector("Password:\r\n" + vendor[2]);
            String answer = (String) login.invoke(v[0], BACKUPS.get(0), REJECT);
            equal(true, verify.invoke(v[0], answer, vendor[1], vendor[0]), "verified backup login " + vendor[0]);
            equal(false, verify.invoke(v[0], "Login incorrect\r\nlogin:", vendor[1], vendor[0]), "exhausted backups " + vendor[0]);
        }
        fixture = collector("Authentication failed: user was locked\r\nUsername:");
        response = (String) login.invoke(fixture[0], BACKUPS.get(0), REJECT);
        equal("backup-a\n", fixture[1].toString().replace("\r", ""), "username rejection does not send password");
        check(CllsFallback.hardStop(response), "stream lock detected");
        fixture = collector("Password:\r\n" + PROMPT);
        response = (String) login.invoke(fixture[0], BACKUPS.get(0), "Authentication failed: user was locked\r\nUsername:");
        equal("", fixture[1].toString(), "locked initial prompt sends nothing");
        Object[] sequence = collector("Password:\r\nError: Username or password error.\r\nUsername:Password:\r\n" + PROMPT);
        CllsFallback.Result result = CllsFallback.attempt(REJECT, "clls", "primary", BACKUPS,
                (credential, previous) -> {
                    try { return (String) login.invoke(sequence[0], credential, previous); }
                    catch (Exception error) { throw new RuntimeException(error); }
                }, candidate -> {
                    try { return (Boolean) verify.invoke(sequence[0], candidate, "QA-NODE", "HW-LLDP-Link_OPTIC"); }
                    catch (Exception error) { throw new RuntimeException(error); }
                });
        equal(2, result.successful.slot, "real stream fallback reaches second account");
        equal("backup-a\nsecret-a\nbackup-b\nsecret-b\n", sequence[1].toString().replace("\r", ""),
                "real stream sends each configured account exactly once");
        Object[] primary = collector("Error: Username or password error.\r\nUsername:");
        Method wait = Telnet_Multi.class.getDeclaredMethod("waitForPostPasswordPrompt", String.class, String.class, String.class);
        wait.setAccessible(true);
        String primaryRejection = (String) wait.invoke(primary[0], "192.0.2.1", "clls", "primary");
        check(CllsFallback.retryableRejection(primaryRejection), "primary Huawei Error response remains complete");
        equal("", primary[1].toString(), "primary rejection does not blindly resend credentials");
        Object[] ssh = collector("");
        set(ssh[0], "in", new ByteArrayInputStream("Password:Error: Username or password error.\r\nUsername:".getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) { return super.read(bytes, offset, Math.min(1, length)); }
        });
        Method sshLogin = Telnet_Multi.class.getDeclaredMethod("completeSshNodeLogin", String.class, String.class, String.class,
                String.class, String.class, String.class);
        sshLogin.setAccessible(true);
        String sshRejection = (String) sshLogin.invoke(ssh[0], "192.0.2.1", "QA-NODE", "HW-LLDP-Link_OPTIC", "Username:", "clls", "primary");
        check(CllsFallback.retryableRejection(sshRejection), "SSH Huawei Error response remains complete");
        equal("clls\nprimary\n", ssh[1].toString().replace("\r", ""), "SSH primary submitted once");
        Object[] zteSsh = collector("");
        set(zteSsh[0], "in", new ByteArrayInputStream(("Password:\r\n% Username or password error\r\nUsername:"
                + "Password:\r\nQA-NODE#").getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) { return super.read(bytes, offset, Math.min(1, length)); }
        });
        String sam = "\u001b[32mPlease Enter IP address\u001b[0m\r\n192.0.2.1\r\n"
                + "Enter IP address [press q/Q to quit]: 192.0.2.1\r\nTrying to connect to 192.0.2.1 ,please wait ...\r\nUsername:";
        String zteRejection = (String) sshLogin.invoke(zteSsh[0], "192.0.2.1", "QA-NODE", "ZTE-LLDP-Link_OPTIC", sam, "clls", "primary");
        check(CllsFallback.retryableRejection(zteRejection), "real SSH SAM transcript permits explicit ZTE auth fallback");
        CllsFallback.Result zteResult = CllsFallback.attempt(zteRejection, "clls", "primary", BACKUPS,
                (credential, previous) -> {
                    try { return (String) login.invoke(zteSsh[0], credential, previous); }
                    catch (Exception error) { throw new RuntimeException(error); }
                }, candidate -> {
                    try { return (Boolean) verify.invoke(zteSsh[0], candidate, "QA-NODE", "ZTE-LLDP-Link_OPTIC"); }
                    catch (Exception error) { throw new RuntimeException(error); }
                });
        equal(1, zteResult.successful.slot, "real SSH SAM rejection reaches backup success");
        equal("clls\nprimary\nbackup-a\nsecret-a\n", zteSsh[1].toString().replace("\r", ""),
                "SSH historical menu sends primary and backup once each");
    }

    private static final class QuietCollector extends Telnet_Multi {
        private QuietCollector() { super("", "", "", "", "", "", "", "", 0, "", ""); }
        @Override public void logwork(String ignored) { }
    }

    private static Object[] collector(String incoming) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        Telnet_Multi collector = (Telnet_Multi) ((Unsafe) field.get(null)).allocateInstance(QuietCollector.class);
        ByteArrayOutputStream writes = new ByteArrayOutputStream();
        set(collector, "in", new ByteArrayInputStream(incoming.getBytes(StandardCharsets.UTF_8)));
        set(collector, "out", new PrintStream(writes, true, "UTF-8"));
        return new Object[]{collector, writes};
    }
    private static void set(Object collector, String field, Object value) throws Exception {
        Field f = Telnet_Multi.class.getDeclaredField(field); f.setAccessible(true); f.set(collector, value);
    }

    private static void storeCases() throws Exception {
        Method encrypt = TrueCllsCredentialStore.class.getDeclaredMethod("encryptPassword", String.class); encrypt.setAccessible(true);
        Properties properties = new Properties();
        equal(0, TrueCllsCredentialStore.loadBackups(properties).size(), "legacy primary-only store");
        properties.setProperty("clls.username", "clls");
        properties.setProperty("clls.password", (String) encrypt.invoke(null, "primary-secret"));
        properties.setProperty("clls.backup.2.username", "backup-b");
        properties.setProperty("clls.backup.2.password", (String) encrypt.invoke(null, "secret-b"));
        properties.setProperty("clls.backup.5.username", "backup-e");
        properties.setProperty("clls.backup.5.password", (String) encrypt.invoke(null, "secret-e"));
        properties.setProperty("clls.backup.1.username", "partial");
        properties.setProperty("clls.backup.3.username", "corrupt");
        properties.setProperty("clls.backup.3.password", "not-encrypted");
        List<CllsFallback.Credential> loaded = TrueCllsCredentialStore.loadBackups(properties);
        equal(2, loaded.size(), "only complete decryptable optional pairs");
        equal(2, loaded.get(0).slot, "preserve sparse slot");
        equal("secret-b", loaded.get(0).password, "decrypt exact saved password");
        equal(5, loaded.get(1).slot, "fifth backup supported");
        check(!properties.toString().contains("secret-b"), "password stored encrypted");
        Path dir = Files.createTempDirectory("clls-store-"); Path file = dir.resolve("accounts.properties");
        try {
            try (OutputStream output = Files.newOutputStream(file)) { properties.store(output, "fixture"); }
            check(TrueCllsCredentialStore.save(file.toFile(), "new-primary", "new-primary-secret"), "primary save succeeds");
            Properties saved = new Properties();
            try (InputStream input = Files.newInputStream(file)) { saved.load(input); }
            equal("new-primary", saved.getProperty("clls.username"), "primary updated");
            equal(properties.getProperty("clls.backup.2.password"), saved.getProperty("clls.backup.2.password"),
                    "primary-only save preserves encrypted backup bytes");
            equal(2, TrueCllsCredentialStore.loadBackups(saved).size(), "backups survive old CLI save");
            check(!new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains("new-primary-secret"), "primary encryption retained");
            String primaryEncrypted = saved.getProperty("clls.password");
            check(TrueCllsCredentialStore.saveBackup(file.toFile(), 4, "backup-four", "four-secret"), "backup setter succeeds");
            try (InputStream input = Files.newInputStream(file)) { saved.load(input); }
            equal(primaryEncrypted, saved.getProperty("clls.password"), "backup setter preserves primary ciphertext");
            equal(properties.getProperty("clls.backup.2.password"), saved.getProperty("clls.backup.2.password"), "backup setter preserves other slots");
            check(!TrueCllsCredentialStore.saveBackup(file.toFile(), 6, "bad", "bad"), "reject sixth slot");
            byte[] malformed = "clls.username=before\nclls.backup.1.username=\\uZZZZ\n".getBytes(StandardCharsets.UTF_8);
            Files.write(file, malformed);
            check(!TrueCllsCredentialStore.save(file.toFile(), "new-primary", "new-secret"), "malformed existing store prevents primary overwrite");
            check(!TrueCllsCredentialStore.saveBackup(file.toFile(), 1, "backup", "secret"), "malformed existing store prevents backup overwrite");
            check(Arrays.equals(malformed, Files.readAllBytes(file)), "malformed source remains byte-identical");
        } finally { Files.deleteIfExists(file); Files.delete(dir); }
        equal(5, TrueCllsCredentialCli.backupSlot(new String[]{"--backup-slot=5", "--stdin"}), "CLI accepts fifth slot");
        equal(0, TrueCllsCredentialCli.backupSlot(new String[]{"--stdin"}), "legacy primary CLI remains default");
        for (String slot : Arrays.asList("0", "6", "invalid")) {
            try { TrueCllsCredentialCli.backupSlot(new String[]{"--backup-slot=" + slot}); throw new AssertionError("invalid CLI slot accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
    }

    private static void eventCases() throws Exception {
        String event = CllsFallback.successEvent("192.0.2.1", "QA-\"NODE\"\n", BACKUPS.get(0));
        check(event.contains("\"slot\":1") && event.contains("\"username\":\"backup-a\""), "non-secret slot identity");
        check(!event.contains("secret-a") && !event.contains("\n"), "event never contains password or raw newlines");
        check(event.contains("\\\"NODE\\\"\\u000a"), "safe JSON escaping");
        equal("", CllsFallback.successEvent("192.0.2.1", "QA", new CllsFallback.Credential(1, "CLLS", "secret")), "literal clls success produces no note");
        Path dir = Files.createTempDirectory("clls-events-"); Path path = dir.resolve("events.jsonl");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 20; i++) tasks.add(pool.submit(() -> { try { CllsFallback.appendEvent(path, event); } catch (IOException ex) { throw new UncheckedIOException(ex); } }));
            for (Future<?> task : tasks) task.get();
            equal(20, Files.readAllLines(path, StandardCharsets.UTF_8).size(), "concurrent appends retain whole events");
        } finally { pool.shutdownNow(); Files.deleteIfExists(path); Files.delete(dir); }
    }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void equal(Object expected, Object actual, String label) { check(Objects.equals(expected, actual), label + ": expected=" + expected + " actual=" + actual); }
}
