package com.java.botgetlog.truecorp;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class DailyCollectionBudgetRegression {
    private static final Clock DAY = Clock.fixed(Instant.parse("2026-09-11T08:00:00Z"), ZoneOffset.UTC);
    private static final String A = "10.163.192.218", B = "10.167.39.51", C = "10.163.192.219";
    private static DailyCollectionBudget budget(Path root) throws Exception { return new DailyCollectionBudget(root,3,DAY); }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "child".equals(args[0])) {
            System.exit(budget(Paths.get(args[1])).start(A,"CHILD") ? 0 : 4);
        }
        Path root=Files.createTempDirectory("daily-budget-test-");
        DailyCollectionBudget first=budget(root);
        check(first.start(A,"PRIMARY"),"primary admitted");
        check(budget(root).start(A,"CPU_RETRY"),"another process state shares quota");
        check(first.start(A,"INCOMPLETE_RETRY"),"third admitted");
        check(!budget(root).start(A,"PORT_MISMATCH"),"all reasons share the same cap");
        check(first.used(A)==3,"denials do not increment or reset count");
        Clock next=Clock.fixed(Instant.parse("2026-09-11T17:00:00Z"),ZoneOffset.UTC);
        check(new DailyCollectionBudget(root,3,next).used(A)==0,"quota uses Bangkok midnight");
        check(new DailyCollectionBudget(root,3,next).start(A,"NEW_DAY"),"new day admitted");
        check(first.used(A)==3,"previous day preserved");

        Path pairRoot=Files.createTempDirectory("daily-pair-test-");
        DailyCollectionBudget pairs=budget(pairRoot);
        pairs.start(A,"PRIMARY");pairs.start(A,"CPU");
        Set<String> selected=new HashSet<>(Arrays.asList(A,B,C));
        check(pairs.reservePairs(Arrays.asList(new String[]{A,B},new String[]{A,C},new String[]{B,A}),selected).isEmpty(),"shared endpoint reserved once");
        check(pairs.used(A)==3&&pairs.used(B)==1&&pairs.used(C)==1,"one slot per unique IP in pair batch");
        check(pairs.start(A,"PAIR")&&pairs.start(B,"PAIR")&&pairs.start(C,"PAIR"),"both pair reservations consumed");
        check(pairs.used(A)==3&&pairs.used(B)==1,"starting reserved work does not count twice");
        Set<String> blocked=budget(pairRoot).reservePairs(Collections.singletonList(new String[]{B,A}),selected);
        check(blocked.equals(new HashSet<>(Arrays.asList(A,B))),"same pair not retried after restart");
        check(pairs.used(B)==1,"repeated pair does not consume peer quota");
        Path exhaustedRoot=Files.createTempDirectory("daily-full-test-");
        DailyCollectionBudget exhausted=budget(exhaustedRoot);
        for(int i=0;i<3;i++)exhausted.start(A,"TEST");
        check(exhausted.reservePairs(Collections.singletonList(new String[]{A,B}),selected).size()==2,"one exhausted side defers both");
        check(exhausted.used(B)==0,"other side not charged when pair cannot run");

        Path concurrentRoot=Files.createTempDirectory("daily-concurrent-test-");
        AtomicInteger admitted=new AtomicInteger();ExecutorService pool=Executors.newFixedThreadPool(20);
        List<Future<?>> futures=new ArrayList<>();
        for(int i=0;i<20;i++)futures.add(pool.submit(()->{try{if(budget(concurrentRoot).start(A,"PARALLEL"))admitted.incrementAndGet();}catch(Exception e){throw new RuntimeException(e);}}));
        for(Future<?> f:futures)f.get();pool.shutdown();
        check(admitted.get()==3,"twenty workers cannot exceed three");

        Path processRoot=Files.createTempDirectory("daily-process-test-");
        List<Process> children=new ArrayList<>();
        String java=Paths.get(System.getProperty("java.home"),"bin","java").toString();
        for(int i=0;i<8;i++)children.add(new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),
                DailyCollectionBudgetRegression.class.getName(),"child",processRoot.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start());
        int granted=0;for(Process child:children){int code=child.waitFor();check(code==0||code==4,"child failed");if(code==0)granted++;}
        check(granted==3&&budget(processRoot).used(A)==3,"OS locks coordinate separate JVMs");

        Path broken=Files.createTempDirectory("daily-corrupt-test-");
        DailyCollectionBudget invalid=budget(broken);invalid.start(A,"PRIMARY");
        Files.write(broken.resolve("2026-09-11/counts/"+A+".properties"),"used=invalid\n".getBytes("UTF-8"));
        boolean failed=false;try{invalid.start(A,"RETRY");}catch(java.io.IOException expected){failed=true;}
        check(failed,"corrupt state defers instead of resetting quota");
        failed=false;try{new DailyCollectionBudget(root,4,DAY);}catch(java.io.IOException expected){failed=true;}
        check(failed,"conflicting processes cannot silently use different limits");
        System.out.println("Daily collection budget regressions passed.");
    }
}
