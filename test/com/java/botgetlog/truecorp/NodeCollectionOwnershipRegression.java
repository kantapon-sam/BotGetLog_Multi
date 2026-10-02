package com.java.botgetlog.truecorp;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import sun.misc.Unsafe;

/** Real retry-queue replay and thread/process contention, without device access. */
public final class NodeCollectionOwnershipRegression {
    private static final String IP="10.163.195.47", SET="HW-LLDP-Link_OPTIC";
    private static int checks;
    private static void check(boolean ok,String why) { checks++; if(!ok)throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        if(args.length>0 && args[0].equals("child")) {
            try(NodeCollectionLease lease=NodeCollectionLease.tryAcquire(Paths.get(args[1]),IP)) {
                System.exit(lease==null?4:0);
            }
        }
        Path root=Files.createTempDirectory("node-ownership-"), logs=root.resolve("Total_Log");Files.createDirectories(logs);
        DailyCollectionBudget budget=new DailyCollectionBudget(root.resolve("budget"),3,Clock.systemUTC());
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            try(NodeCollectionLease first=NodeCollectionLease.acquire(logs,IP,()->false)) {
                try(NodeCollectionLease nested=NodeCollectionLease.tryAcquire(logs,IP)) { check(nested!=null,"owner can reenter for cleanup"); }
                Future<Boolean> busy=pool.submit(()->{try(NodeCollectionLease l=NodeCollectionLease.tryAcquire(logs,IP)){return l==null;}});
                check(busy.get(3,TimeUnit.SECONDS),"monitor and retry cannot own same IP");
                Future<Boolean> other=pool.submit(()->{try(NodeCollectionLease l=NodeCollectionLease.tryAcquire(logs,"10.163.195.48")){return l!=null;}});
                check(other.get(3,TimeUnit.SECONDS),"different IPs remain parallel");
                check(child(logs)==4,"separate JVM excluded while owner remains");
                AtomicBoolean cancelled=new AtomicBoolean();
                Future<Boolean> waiter=pool.submit(()->{try(NodeCollectionLease l=NodeCollectionLease.acquire(logs,IP,cancelled::get)){budget.start(IP,"UNEXPECTED");return false;}catch(InterruptedException expected){return true;}});
                cancelled.set(true);check(waiter.get(3,TimeUnit.SECONDS),"cancel waiting task");
                check(budget.used(IP)==0,"waiting/cancelled tasks do not consume admission");
                Path file=logs.resolve("[25644]"+IP+"_CPE-CCS8620_"+SET+"_2026-10-02.txt");
                Files.write(file,"partial".getBytes(StandardCharsets.UTF_8));file.toFile().setLastModified(1);
                check(!pool.submit(()->Telnet_Multi.moveLogToArchiveIfInactive(file.toFile(),"monitor")).get(3,TimeUnit.SECONDS),"monitor cannot archive another owner's transcript");
                check(Files.exists(file),"active transcript preserved");
            }
            check(child(logs)==0,"OS ownership released after completion");
            AtomicInteger writers=new AtomicInteger(),maximum=new AtomicInteger();
            List<Future<?>> workers=new ArrayList<>();
            for(int n=0;n<12;n++) workers.add(pool.submit(()->{
                try(NodeCollectionLease l=NodeCollectionLease.acquire(logs,IP,()->false)) {
                    int active=writers.incrementAndGet();maximum.accumulateAndGet(active,Math::max);
                    Thread.sleep(15);writers.decrementAndGet();
                } catch(Exception e){throw new RuntimeException(e);}
            }));
            for(Future<?> worker:workers)worker.get(10,TimeUnit.SECONDS);
            check(maximum.get()==1,"all retry sources serialize writes");
            retryQueueReplay(root.resolve("replay"),pool,budget);
            String event="[FAIL] test\n";
            check(Telnet_Multi.withAttemptId(event,"fixture-id").equals("[FAIL] test [ATTEMPT=fixture-id]\n"),"terminal event has exact attempt identity");
            check(Telnet_Multi.withAttemptId("[CMD -> "+IP+"] show port\n","a").endsWith("show port\n"),"command transcript unchanged");
            if(args.length>0) {
                File fixture=new File(args[0]);
                check(!BotGetLog_TrueCorp.isLogCompleteForCmdSet(fixture,"N-LLDP-Link_OPTIC"),"CCS8620 mixed incomplete fixture never accepted");
            }
        } finally {pool.shutdownNow();}
        System.out.println("PASS NodeCollectionOwnershipRegression checks="+checks);
    }
    private static int child(Path logs) throws Exception {
        Process p=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),NodeCollectionOwnershipRegression.class.getName(),"child",logs.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
        check(p.waitFor(10,TimeUnit.SECONDS),"child exits");return p.exitValue();
    }
    @SuppressWarnings("unchecked") private static void retryQueueReplay(Path root,ExecutorService pool,DailyCollectionBudget budget) throws Exception {
        Path logs=root.resolve("Total_Log");Files.createDirectories(logs);
        Field unsafeField=Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);Unsafe unsafe=(Unsafe)unsafeField.get(null);
        PathFile input=(PathFile)unsafe.allocateInstance(PathFile.class);
        Field path=PathFile.class.getDeclaredField("Log");path.setAccessible(true);path.set(input,logs.toString());
        Class<?> taskType=Class.forName(BotGetLog_TrueCorp.class.getName()+"$NodeCommandTask");
        Constructor<?> ctor=taskType.getDeclaredConstructors()[0];ctor.setAccessible(true);Object task=ctor.newInstance(25644,"CPE-CCS8620",IP,SET,0);
        Class<?> retryType=Class.forName(BotGetLog_TrueCorp.class.getName()+"$NetworkRetryTask");
        ctor=retryType.getDeclaredConstructors()[0];ctor.setAccessible(true);
        Object retry=ctor.newInstance(task,input,"","","","","","","screen-length 0 temporary","quit");
        Method run=null;for(Method m:BotGetLog_TrueCorp.class.getDeclaredMethods())if(m.getName().equals("runNetworkRetryAttempt")){run=m;break;}
        run.setAccessible(true);final Method runMethod=run;
        for(String[] config:new String[][]{{"CMDSET_FIRST_COMMAND_CACHE","screen-length 0 temporary"},{"CMDSET_LAST_COMMAND_CACHE","quit"}}) {
            Field cache=BotGetLog_TrueCorp.class.getDeclaredField(config[0]);cache.setAccessible(true);((Map<String,String>)cache.get(null)).put(SET.toLowerCase(Locale.ROOT),config[1]);
        }
        Path log=logs.resolve("[25644]"+IP+"_CPE-CCS8620_"+SET+"_2026-10-02.txt");
        byte[] incomplete="<CPE-CCS8620>screen-length 0 temporary\n".getBytes(StandardCharsets.UTF_8);
        Files.write(log,incomplete);log.toFile().setLastModified(1);
        Future<Object> queued;
        try(NodeCollectionLease monitor=NodeCollectionLease.acquire(logs,IP,()->false)) {
            CountDownLatch entered=new CountDownLatch(1);
            queued=pool.submit(()->{entered.countDown();return runMethod.invoke(null,retry,null,1);});
            check(entered.await(3,TimeUnit.SECONDS),"network retry started while monitor owns IP");
            try {queued.get(300,TimeUnit.MILLISECONDS);throw new AssertionError("retry bypassed monitor ownership");}
            catch(TimeoutException expected){check(true,"network retry waits before archive/admission");}
            check(Arrays.equals(Files.readAllBytes(log),incomplete),"waiting retry does not archive or modify partial log");
            Files.write(log,("<CPE-CCS8620>screen-length 0 temporary\n<CPE-CCS8620>display interface\nInterface data\n<CPE-CCS8620>quit\n").getBytes(StandardCharsets.UTF_8));
            check(BotGetLog_TrueCorp.isLogCompleteForCmdSet(log.toFile(),SET),"monitor publishes a validated complete log");
        }
        Object result=queued.get(5,TimeUnit.SECONDS);Field status=result.getClass().getDeclaredField("status");status.setAccessible(true);
        check(status.get(result).toString().equals("SUCCESS"),"retry rechecks completed monitor output without opening a gateway");
        check(budget.used(IP)==0,"completed owner does not cost an extra retry admission");
    }
}
