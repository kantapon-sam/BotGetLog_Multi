package com.java.botgetlog.truecorp;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class DailyUniqueNodeBudgetRegression {
    static final Clock NOW = Clock.fixed(Instant.parse("2026-10-30T08:00:00Z"), ZoneOffset.UTC);
    static int checks;
    static void check(boolean v, String m) { checks++; if (!v) throw new AssertionError(m); }
    static DailyCollectionBudget budget(Path p) throws Exception { return new DailyCollectionBudget(p, 3, NOW); }
    static Path temp() throws Exception { return Files.createTempDirectory("unique-node-budget-"); }
    static String ip(int n) { return "10.167.1." + n; }
    static void write(Path p, String s) throws Exception { Files.createDirectories(p.getParent()); Files.write(p, s.getBytes(StandardCharsets.UTF_8)); }
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("child")) {
            System.exit(budget(Paths.get(args[1])).start(args[2], "CHILD") ? 0 : 4);
        }
        System.setProperty("true.daily.unique.limit", "2");
        Path root = temp();
        DailyCollectionBudget b = budget(root);
        check(b.start(ip(1), "MAIN") && b.start(ip(2), "RX_SFP"), "phases share two unique slots");
        check(b.start(ip(1), "RING_RETRY"), "same IP retry does not consume another unique slot");
        check(!budget(root).start(ip(3), "RANDOM_RING"), "restart retains the total cap");
        check("DAILY_UNIQUE_LIMIT".equals(b.availability(ip(3)).reason), "explicit cap diagnostic");
        check(b.used(ip(3)) == 0, "denied node spends no per-IP attempt");
        Set<String> both = new HashSet<>(Arrays.asList(ip(3), ip(4)));
        check(b.reservePairs(Collections.singletonList(new String[]{ip(3), ip(4)}), both).size() == 2, "unproven pair cannot exceed cap");
        check(b.used(ip(3)) == 0 && b.used(ip(4)) == 0, "denied pair is atomic");
        Path evidence = root.resolve("exceptions.tsv");
        write(evidence, "version=1\nday=2026-10-30\n" + ip(3) + "\tLLDP_ONE_WAY\taudit.csv\n" + ip(4) + "\tLLDP_ONE_WAY\taudit.csv\n");
        System.setProperty("true.daily.unique.exceptionsFile", evidence.toString());
        DailyCollectionBudget permitted = budget(root);
        check(permitted.reservePairs(Collections.singletonList(new String[]{ip(3), ip(4)}), both).isEmpty(), "verified LLDP endpoints may exceed cap together");
        check(permitted.start(ip(3), "PAIR") && permitted.start(ip(4), "PAIR"), "reserved exceptions run");
        check(permitted.used(ip(3)) == 1 && permitted.used(ip(4)) == 1, "pair start does not charge twice");
        check(!permitted.start(ip(5), "OTHER"), "LLDP overflow does not open ordinary slots");
        System.clearProperty("true.daily.unique.exceptionsFile");
        check(budget(root).start(ip(3), "INCOMPLETE_RETRY"), "admitted IP remains same unique node without renewed exception");

        Path bad = temp().resolve("exceptions.tsv");
        write(bad, "version=1\nday=2026-10-29\n");
        System.setProperty("true.daily.unique.exceptionsFile", bad.toString());
        boolean rejected = false;
        try { budget(temp()); } catch (java.io.IOException expected) { rejected = true; }
        check(rejected, "stale-day exceptions fail closed");
        write(bad, "version=1\nday=2026-10-30\n" + ip(9) + "\tCHAIN_SUBCHAIN\tunchanged ring\n");
        rejected = false;
        try { budget(temp()); } catch (java.io.IOException expected) { rejected = true; }
        check(rejected, "unchanged Chain membership is not an LLDP exception");
        System.clearProperty("true.daily.unique.exceptionsFile");

        System.setProperty("true.daily.unique.limit", "3");
        Path parallel = temp();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        AtomicInteger admitted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            final String address = ip(i);
            futures.add(pool.submit(() -> { try { if (budget(parallel).start(address, "CONCURRENT")) admitted.incrementAndGet(); }
                catch (Exception failure) { throw new RuntimeException(failure); } }));
        }
        for (Future<?> future : futures) future.get();
        pool.shutdown();
        check(admitted.get() == 3, "20 workers cannot overshoot a three-IP limit");

        Path processes = temp(); List<Process> children = new ArrayList<>();
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        for (int i = 1; i <= 8; i++) children.add(new ProcessBuilder(java, "-Xmx96m", "-Dtrue.daily.unique.limit=3", "-cp", System.getProperty("java.class.path"),
            DailyUniqueNodeBudgetRegression.class.getName(), "child", processes.toString(), ip(i)).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start());
        int started = 0;
        for (Process child : children) { int code = child.waitFor(); check(code == 0 || code == 4, "child failed"); if (code == 0) started++; }
        check(started == 3, "separate JVMs share the same distinct cap");

        System.setProperty("true.daily.unique.limit", "0");
        Path old = temp(); DailyCollectionBudget uncapped = budget(old);
        for (int i = 1; i <= 5; i++) check(uncapped.start(ip(i), "FULL"), "uncapped Full behavior preserved");
        System.setProperty("true.daily.unique.limit", "2");
        check(!budget(old).start(ip(6), "LATER"), "preexisting attempts are included when enabling a cap");
        check(budget(old).start(ip(1), "LATER_RETRY"), "preexisting IP is still one node");

        System.clearProperty("true.daily.unique.limit");
        Path resumed = temp();
        write(resumed.resolve("2026-10-30/unique-node-policy.properties"), "version=1\nday=2026-10-30\nlimit=1\n");
        check(budget(resumed).start(ip(1), "FIRST"), "frozen daily policy loaded without environment override");
        check(!budget(resumed).start(ip(2), "RESUME"), "resume cannot bypass saved daily policy");
        Clock next = Clock.fixed(Instant.parse("2026-10-30T13:00:00Z"), ZoneOffset.UTC);
        check(new DailyCollectionBudget(resumed, 3, next).start(ip(2), "NEXT"), "new 20:00 cycle has separate state");
        Path ledger = resumed.resolve("2026-10-30/unique-node-admissions.tsv");
        Files.write(ledger, "partial".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        rejected = false;
        try { budget(resumed).start(ip(3), "CORRUPT"); } catch (java.io.IOException expected) { rejected = true; }
        check(rejected, "truncated admission journal never resets the cap");
        System.out.println("PASS DailyUniqueNodeBudgetRegression checks=" + checks);
    }
}
