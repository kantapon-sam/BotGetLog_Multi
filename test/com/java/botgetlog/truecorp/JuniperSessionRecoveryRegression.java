package com.java.botgetlog.truecorp;

import java.io.*;
import java.lang.reflect.*;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.Permission;
import java.util.*;
import sun.misc.Unsafe;

/** Offline Junos login, pagination and interrupted-collection replay. */
public final class JuniperSessionRecoveryRegression {
    private static final String SET = "J-LLDP-Link_OPTIC";
    private static final String CONFIGURED = "CN_TEST-CORE-1_re0";
    private static final String ACTUAL = "CN_TEST-CORE-1_re1";
    private static final String PROMPT = "operator@clls@" + ACTUAL + ">";
    private static final String IP = "192.0.2.21";
    private static final String DAY = java.time.LocalDate.now().toString();
    private static final String[] COMMANDS = {SET, "set cli screen-length 0", "show version", "quit"};
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("juniper-session-recovery-");
        String previousSettle = System.getProperty("botgetlog.command.promptSettle.ms");
        SecurityManager previousSecurity = System.getSecurityManager();
        NetworkGuard networkGuard = new NetworkGuard();
        System.setSecurityManager(networkGuard);
        System.setProperty("botgetlog.command.promptSettle.ms", "100");
        try {
            cacheCommands();
            loginCases(root);
            pagerCases(root);
            completeCollection(root);
            interruptedCollection(root, "closed", new ByteArrayInputStream((PROMPT
                    + "show version\nJunos partial page\nConnection closed by foreign host.\n").getBytes(StandardCharsets.UTF_8)));
            interruptedCollection(root, "timeout", new InputStream() {
                @Override public int read() throws IOException { throw new SocketTimeoutException("Read timed out"); }
            });
            interruptedCollection(root, "eof", new ByteArrayInputStream((PROMPT
                    + "show version\nJunos partial page\n").getBytes(StandardCharsets.UTF_8)));
            wrongPromptCase(root, PROMPT.replace("_re1", "_re0"));
            wrongPromptCase(root, "operator@clls@OTHER-CORE-1_re1>");
            equal("no network connection attempted", 0, networkGuard.attempts);
            System.out.println("PASS JuniperSessionRecoveryRegression checks=" + checks);
        } finally {
            System.setSecurityManager(previousSecurity);
            if (previousSettle == null) System.clearProperty("botgetlog.command.promptSettle.ms");
            else System.setProperty("botgetlog.command.promptSettle.ms", previousSettle);
            Field writersField = Telnet_Multi.class.getDeclaredField("TELNET_LOG_WRITERS");
            writersField.setAccessible(true);
            Map<?, ?> writers = (Map<?, ?>) writersField.get(null);
            for (Object writer : writers.values()) ((Writer) writer).close();
            writers.clear();
            TrueDeviceInventoryUpdater.reset();
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (IOException e) { throw new UncheckedIOException(e); }
                });
            }
        }
    }

    private static void loginCases(Path root) throws Exception {
        Fixture f = fixture(root, "login");
        equal("re0 inventory accepts same chassis re1 at login", true,
                login(f.collector, "\r\n{master}\r\n" + PROMPT + " ", CONFIGURED));
        equal("capture actual RE", ACTUAL, value(f.collector, "runtimeDeviceName"));
        equal("accept captured RE again", true, login(f.collector, PROMPT, CONFIGURED));
        equal("reject RE change after login", false,
                login(f.collector, PROMPT.replace("_re1", "_re0"), CONFIGURED));
        equal("reject other chassis after login", false,
                login(f.collector, "operator@clls@OTHER-CORE-1_re1>", CONFIGURED));
        equal("pin unchanged on rejection", ACTUAL, value(f.collector, "runtimeDeviceName"));
        equal("strict stream check uses captured RE", true, promptMatches(PROMPT, ACTUAL));
        equal("strict stream check still rejects stale RE", false, promptMatches(PROMPT, CONFIGURED));
        equal("strict stream check rejects command echo", false, promptMatches(PROMPT + " show version", ACTUAL));
        for (String node : new String[]{CONFIGURED, "CN-TEST-CORE-1-RE0", ACTUAL, "CN-PLAIN"}) {
            Fixture exact = fixture(root, "exact");
            equal("exact normalized login " + node, true, login(exact.collector, "operator@" + node + ">", node));
        }
        for (String response : new String[]{"operator@OTHER-CORE-1_re1>", "operator@CN_TEST-CORE-2_re1>",
                "operator@CN_TEST-CORE-1_re10>", "operator@CORE-1_re1>", "operator@CN_TEST-CORE-1>",
                "Description: " + PROMPT, PROMPT + " show version", "<" + ACTUAL + ">", "", "Password:",
                "[TIMEOUT-READ]\n" + PROMPT, "Login incorrect\n" + PROMPT}) {
            Fixture rejected = fixture(root, "reject");
            equal("reject unrelated/incomplete login " + response, false, login(rejected.collector, response, CONFIGURED));
            equal("rejected login does not adopt identity", "", value(rejected.collector, "runtimeDeviceName"));
        }
        equal("empty configured identity", false, login(fixture(root, "empty").collector, PROMPT, ""));
        equal("RE suffix without chassis", false, login(fixture(root, "no-base").collector, "operator@re1>", "re0"));
    }

    private static void pagerCases(Path root) throws Exception {
        for (String marker : new String[]{"---(more)---", "---(more 50%)---", "---(more 100%)---"}) {
            Fixture f = fixture(root, "pager");
            adopt(f);
            Files.write(f.log, header().getBytes(StandardCharsets.UTF_8));
            final String commandSent = COMMANDS[2] + System.lineSeparator();
            String suffix = marker.substring("---(more".length());
            set(f.collector, "in", new FragmentedPagerStream(f.sent, commandSent,
                    PROMPT + " show version\r\nJunos first page\r\n---(", "more", suffix,
                    "\r\nJunos last page\r\n{master}\r\n" + PROMPT));
            equal("fragmented pager collection succeeds " + marker, true, execute(f.collector, COMMANDS[2], false));
            equal("send exactly one pagination space " + marker, commandSent + " ", f.sent.toString("UTF-8"));
            String log = text(f.log);
            equal("persist last page " + marker, true, log.contains("Junos last page"));
            equal("normal paged collection has no failure", false, f.collector.hasSessionFailureRecorded());
            f.collector.disconnect();
        }
        for (String marker : new String[]{"--more--", "---- more ----", "more?"}) {
            equal("other vendor pager retained " + marker, true, pagination(marker));
        }
        equal("ordinary output is not a pager", false, pagination("Description: more bandwidth available"));
    }

    private static void completeCollection(Path root) throws Exception {
        Fixture f = fixture(root, "complete");
        adopt(f);
        String[] responses = {
            PROMPT + " " + COMMANDS[1] + "\nScreen length set to 0\n{master}\n" + PROMPT,
            " " + COMMANDS[2] + "\nHostname: " + ACTUAL + "\nJunos: test-version\n" + PROMPT,
            " quit Connection closed by foreign host.\n"
        };
        for (int i = 1; i < COMMANDS.length; i++) {
            set(f.collector, "in", new ByteArrayInputStream(responses[i - 1].getBytes(StandardCharsets.UTF_8)));
            equal("complete command " + COMMANDS[i], true, execute(f.collector, COMMANDS[i], i == COMMANDS.length - 1));
        }
        equal("actual RE filename", true, f.log.getFileName().toString().contains("-re1_"));
        equal("complete transcript validates", true, BotGetLog_TrueCorp.isLogCompleteForCmdSet(f.log.toFile(), SET));
        equal("completed collection has no failure", false, f.collector.hasSessionFailureRecorded());
        equal("all commands sent in order", String.join(System.lineSeparator(), Arrays.copyOfRange(COMMANDS, 1, COMMANDS.length))
                + System.lineSeparator(), f.sent.toString("UTF-8"));
        f.collector.disconnect();
    }

    private static void interruptedCollection(Path root, String label, InputStream input) throws Exception {
        Fixture f = fixture(root, label);
        adopt(f);
        Files.write(f.log, header().getBytes(StandardCharsets.UTF_8));
        set(f.collector, "in", input);
        equal("interrupted command stops current collection " + label, false, execute(f.collector, COMMANDS[2], false));
        equal("interruption records failure " + label, true, f.collector.hasSessionFailureRecorded());
        equal("interruption remains eligible for outer network retry " + label, true, f.collector.hasRetryableNetworkFailureRecorded());
        equal("do not send reconnect or next command " + label, COMMANDS[2] + System.lineSeparator(), f.sent.toString("UTF-8"));
        equal("partial log cannot be marked complete " + label, false, BotGetLog_TrueCorp.isLogCompleteForCmdSet(f.log.toFile(), SET));
        equal("interruption retains established RE " + label, ACTUAL, value(f.collector, "runtimeDeviceName"));
    }

    private static void wrongPromptCase(Path root, String wrongPrompt) throws Exception {
        Fixture f = fixture(root, "wrong-prompt");
        adopt(f);
        Files.write(f.log, header().getBytes(StandardCharsets.UTF_8));
        set(f.collector, "in", new ByteArrayInputStream((PROMPT + " show version\nPartial data\n" + wrongPrompt).getBytes(StandardCharsets.UTF_8)));
        equal("reject wrong stream prompt " + wrongPrompt, false, execute(f.collector, COMMANDS[2], false));
        equal("log scan cannot change captured identity", ACTUAL, value(f.collector, "runtimeDeviceName"));
        equal("failed transcript retains original filename", true, Files.exists(f.log));
    }

    private static void adopt(Fixture fixture) throws Exception {
        equal("fixture authenticated", true, login(fixture.collector, PROMPT, CONFIGURED));
    }
    private static String header() { return PROMPT + " " + COMMANDS[1] + "\nScreen length set to 0\n"; }
    private static String text(Path file) throws IOException { return new String(Files.readAllBytes(file), StandardCharsets.UTF_8); }
    private static boolean login(Telnet_Multi collector, String response, String configured) throws Exception {
        return (Boolean) call(collector, "acceptJuniperLoginPrompt", new Class<?>[]{String.class, String.class}, response, configured);
    }
    private static boolean execute(Telnet_Multi collector, String command, boolean last) throws Exception {
        return (Boolean) call(collector, "executeCommandWithReconnect", new Class<?>[]{String.class, String.class, String.class, int.class, String.class, boolean.class},
                IP, CONFIGURED, SET, 1, command, last);
    }
    private static boolean promptMatches(String response, String node) throws Exception {
        return (Boolean) call(null, "matchesCollectionPrompt", new Class<?>[]{StringBuilder.class, String[].class, String[].class, String.class, boolean.class},
                new StringBuilder(response.toLowerCase(Locale.ROOT)), new String[]{">"}, new String[]{">"}, node, false);
    }
    private static boolean pagination(String response) throws Exception {
        return (Boolean) call(null, "containsPaginationMarker", new Class<?>[]{StringBuilder.class}, new StringBuilder(response));
    }
    private static Object call(Telnet_Multi collector, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = Telnet_Multi.class.getDeclaredMethod(name, types); method.setAccessible(true);
        return method.invoke(collector, args);
    }
    private static Object value(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static Fixture fixture(Path root, String label) throws Exception {
        Path directory = Files.createTempDirectory(root, label + "-");
        Path log = directory.resolve("[1]" + IP + "_" + ACTUAL.replace('_', '-') + "_" + SET + "_" + DAY + ".txt");
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        Telnet_Multi collector = (Telnet_Multi) unsafe.allocateInstance(Telnet_Multi.class);
        PathFile paths = (PathFile) unsafe.allocateInstance(PathFile.class);
        set(paths, "Log", directory.toString()); set(paths, "LogWork", directory.toString());
        set(collector, "FileInput", paths); set(collector, "LOG", new StringBuilder());
        set(collector, "formattedDateTimeLOG", "fixture"); set(collector, "formattedDateTime", DAY);
        set(collector, "preparedLogSessionKey", log.toAbsolutePath().toString());
        set(collector, "completedExitWarning", ""); set(collector, "runtimeDeviceName", "");
        set(collector, "expectedCollectionCommands", COMMANDS.clone()); set(collector, "s", PROMPT);
        set(collector, "in", new ByteArrayInputStream(new byte[0]));
        ByteArrayOutputStream sent = new ByteArrayOutputStream(); set(collector, "out", new PrintStream(sent));
        Files.write(log, new byte[0]);
        return new Fixture(collector, sent, log);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void cacheCommands() throws Exception {
        for (String name : new String[]{"CMDSET_FIRST_COMMAND_CACHE", "CMDSET_LAST_COMMAND_CACHE", "CMDSET_COMMAND_CACHE"}) {
            Field field = BotGetLog_TrueCorp.class.getDeclaredField(name); field.setAccessible(true);
            ((Map) field.get(null)).put(SET.toLowerCase(Locale.ROOT), name.equals("CMDSET_FIRST_COMMAND_CACHE")
                    ? COMMANDS[1] : name.equals("CMDSET_LAST_COMMAND_CACHE") ? "quit" : Arrays.asList(COMMANDS));
        }
    }
    private static void equal(String label, Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
    }
    private static final class Fixture {
        final Telnet_Multi collector; final ByteArrayOutputStream sent; final Path log;
        Fixture(Telnet_Multi collector, ByteArrayOutputStream sent, Path log) { this.collector=collector; this.sent=sent; this.log=log; }
    }
    private static final class FragmentedPagerStream extends InputStream {
        final ByteArrayOutputStream sent; final String command; final String[] chunks; int index;
        FragmentedPagerStream(ByteArrayOutputStream sent, String command, String... chunks) { this.sent=sent; this.command=command; this.chunks=chunks; }
        @Override public int read() { throw new AssertionError("Expected buffered collection reads"); }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (index == chunks.length) return -1;
            if (index == chunks.length - 1 && !(command + " ").equals(sent.toString("UTF-8"))) {
                throw new IOException("Pager continuation requires exactly one space");
            }
            byte[] chunk = chunks[index++].getBytes(StandardCharsets.UTF_8);
            if (chunk.length > length) throw new AssertionError("Fixture chunk exceeds buffer");
            System.arraycopy(chunk, 0, bytes, offset, chunk.length); return chunk.length;
        }
    }
    private static final class NetworkGuard extends SecurityManager {
        int attempts;
        @Override public void checkPermission(Permission permission) { }
        @Override public void checkConnect(String host, int port) {
            // Log4j asks for the local hostname (port -1) during POI initialization.
            if (port < 0) return;
            attempts++; throw new AssertionError("Offline test attempted a socket connection");
        }
        @Override public void checkConnect(String host, int port, Object context) { checkConnect(host, port); }
    }
}
