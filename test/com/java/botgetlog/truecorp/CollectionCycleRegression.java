package com.java.botgetlog.truecorp;

import java.nio.file.*;
import java.time.*;
import java.util.*;

public class CollectionCycleRegression {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static Clock at(String instant) { return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC); }
    public static void main(String[] args) throws Exception {
        String[][] cases = {
            {"2026-10-25T12:59:59Z", "2026-10-25"},
            {"2026-10-25T13:00:00Z", "2026-10-26"},
            {"2026-10-25T16:59:59Z", "2026-10-26"},
            {"2026-10-25T17:00:00Z", "2026-10-26"},
            {"2026-10-26T12:59:59Z", "2026-10-26"},
            {"2026-10-26T13:00:00Z", "2026-10-27"},
            {"2026-12-31T13:00:00Z", "2027-01-01"}
        };
        for (String[] test : cases) check(test[1].equals(CollectionCycle.resolve(at(test[0]), null)), test[0]);
        for (String[] test : cases) check("2026-10-26".equals(CollectionCycle.resolve(at(test[0]), "2026-10-26")), "assigned cycle pinned");
        for (String invalid : Arrays.asList("", "2026-02-30", "2026-1-2", "../2026-10-26")) {
            boolean rejected=false;
            try { CollectionCycle.resolve(at(cases[0][0]), invalid); } catch (RuntimeException expected) { rejected=true; }
            check(rejected, "invalid day rejected");
        }
        Path root=Files.createTempDirectory("cycle-budget-");
        MutableClock clock=new MutableClock(Instant.parse("2026-10-25T13:00:00Z"));
        DailyCollectionBudget budget=new DailyCollectionBudget(root,3,clock);
        check(budget.start("10.1.1.1", "PRIMARY"), "primary admitted");
        clock.instant=Instant.parse("2026-10-25T17:00:00Z");
        check(budget.used("10.1.1.1")==1, "midnight preserves quota");
        check(budget.start("10.1.1.1", "RETRY"), "same cycle retry");
        clock.instant=Instant.parse("2026-10-26T14:00:00Z");
        check(budget.used("10.1.1.1")==2, "long running job remains pinned after next 20:00");
        check(budget.start("10.1.1.1", "LAST") && !budget.start("10.1.1.1", "FOURTH"), "cap cannot reset midrun");
        check(new DailyCollectionBudget(root,3,clock).used("10.1.1.1")==0, "new independent cycle gets new allowance");
        System.out.println("Collection cycle boundary, frozen quota and validation regressions passed.");
    }
    static class MutableClock extends Clock {
        Instant instant;
        MutableClock(Instant value) { instant=value; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(final ZoneId zone) { return new Clock() {
            public ZoneId getZone() { return zone; }
            public Clock withZone(ZoneId other) { return MutableClock.this.withZone(other); }
            public Instant instant() { return MutableClock.this.instant; }
        }; }
        public Instant instant() { return instant; }
    }
}
