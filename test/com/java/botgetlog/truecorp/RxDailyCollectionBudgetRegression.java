package com.java.botgetlog.truecorp;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public class RxDailyCollectionBudgetRegression {
    private static final Clock DAY = Clock.fixed(Instant.parse("2026-09-29T15:00:00Z"), ZoneOffset.UTC);
    private static final String A="10.1.1.1", B="10.1.1.2", C="10.1.1.3";
    private static void check(boolean value,String reason) { if(!value)throw new AssertionError(reason); }
    private static DailyCollectionBudget budget(Path root,Clock clock) throws Exception {
        return new DailyCollectionBudget(root,3,clock,(file,cmd)-> {
            check(cmd.equals("HW-LLDP-Link_OPTIC"),"actual command set extracted from filename");
            return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8).equals("COMPLETE");
        });
    }
    public static void main(String[] args) throws Exception {
        Path temp=Files.createTempDirectory("rx-daily-regression-"), root=temp.resolve("System_Log/budget"), logs=temp.resolve("Total_Log");
        Files.createDirectories(logs);
        DailyCollectionBudget first=budget(root,DAY);
        first.registerDailyOnce(Arrays.asList(A,A,B),"2026-09-29");
        check(first.used(A)==0,"register does not consume allowance");
        check(first.start(A,"PRIMARY"),"first attempt");
        Path log=logs.resolve("[2]"+A+"_CPE-ABC1234_HW-LLDP-Link_OPTIC_2026-09-29.txt");
        Files.write(log,"INCOMPLETE".getBytes(StandardCharsets.UTF_8));
        check(first.available(A),"incomplete remains eligible");
        check(budget(root,DAY).start(A,"INCOMPLETE_RETRY"),"retry survives a new JVM instance");
        Files.write(log,"COMPLETE".getBytes(StandardCharsets.UTF_8));
        check(!first.available(A),"complete log blocks queue selection");
        check(!budget(root,DAY).start(A,"CPU_RETRY"),"CPU cannot duplicate completed Rx node");
        check(!budget(root,DAY).start(A,"BACKGROUND_RETRY"),"background retry also blocked");
        check(first.used(A)==2,"complete denials do not consume third attempt");
        check(first.reservePairs(Collections.singletonList(new String[]{A,B}),new HashSet<>(Arrays.asList(A,B))).size()==2,"LLDP pair does not duplicate completed node");
        check(first.used(B)==0,"LLDP blocked pair does not charge peer");
        for(int i=0;i<3;i++)check(first.start(B,"INCOMPLETE"),"incomplete attempts available");
        check(!first.start(B,"FOURTH"),"incomplete exception still capped at three");
        check(first.start(C,"PRIMARY")&&first.start(C,"CPU")&&first.start(C,"OTHER"),"unrelated nodes retain existing policy");
        Path old=logs.resolve("[4]10.1.1.4_CPE-OLD_HW-LLDP-Link_OPTIC_2026-09-28.txt");
        Files.write(old,"COMPLETE".getBytes(StandardCharsets.UTF_8));
        first.registerDailyOnce(Collections.singletonList("10.1.1.4"),"2026-09-29");
        check(first.start("10.1.1.4","PRIMARY")&&first.available("10.1.1.4"),"yesterday's completed log cannot satisfy today");
        check(!budget(root,DAY).available(A),"additive registration retains earlier nodes");
        Clock tomorrow=Clock.fixed(Instant.parse("2026-09-29T17:00:00Z"),ZoneOffset.UTC);
        DailyCollectionBudget next=budget(root,tomorrow);
        next.registerDailyOnce(Collections.singletonList(A),"2026-09-30");
        check(next.start(A,"NEW_DAY"),"Bangkok midnight starts a new policy and count");
        boolean rejected=false;try{next.registerDailyOnce(Collections.singletonList(B),"2026-09-29");}catch(java.io.IOException expected){rejected=true;}
        check(rejected,"stale day plan rejected");
        ExecutorService pool=Executors.newFixedThreadPool(8);List<Future<Boolean>> results=new ArrayList<>();
        for(int i=0;i<8;i++)results.add(pool.submit(()->budget(root,DAY).start(A,"PARALLEL_DUPLICATE")));
        for(Future<Boolean> result:results)check(!result.get(),"parallel completed duplicates denied");pool.shutdown();
        Files.write(root.resolve("2026-09-29/rx-once-per-day.properties"),"broken".getBytes(StandardCharsets.UTF_8));
        rejected=false;try{budget(root,DAY).available(A);}catch(java.io.IOException expected){rejected=true;}
        check(rejected,"corrupt policy fails closed");
        System.out.println("Rx daily completion policy regressions passed.");
    }
}
