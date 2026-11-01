package com.java.botgetlog.truecorp;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import sun.misc.Unsafe;

/** Filesystem tests only: no operational workbook, credentials or connections. */
public final class ExistingLogPreservationRegression {
    private static final String IP="192.0.2.1", DEVICE="QA-NODE", SET="HW-LLDP-Link_OPTIC";
    private static int checks;
    public static void main(String[] args) throws Exception {
        if (args.length>0 && "environment".equals(args[0])) {
            check(ExistingLogPreservation.enabled(), "inherited environment activates direct collectors");
            System.out.println("PASS preservation inherited environment"); return;
        }
        check(!ExistingLogPreservation.enabled(), "default mode unchanged");
        TrueLinkOpticalAutoMode.Selection selection=selection();
        check(TrueLinkOpticalAutoMode.shouldClearSelectedLogsBeforeRerun(selection), "default selected behavior unchanged");
        ExistingLogPreservation.configure(new String[]{ExistingLogPreservation.ARGUMENT});
        check(ExistingLogPreservation.enabled(), "explicit flag activates preservation");
        check(!TrueLinkOpticalAutoMode.shouldClearSelectedLogsBeforeRerun(selection), "selected bulk cleanup suppressed");
        check(TrueLinkOpticalAutoMode.shouldSkipGlobalDuplicateCleanup(new String[0]), "global dedup suppressed without extra flag");
        cacheCommands();
        Path root=Files.createTempDirectory("preserve-logs-regression-");
        for (String content : Arrays.asList("<QA-NODE>screen-length 0 temporary\n", "INVALID HEADER bytes retained\n")) {
            Path folder=Files.createTempDirectory(root,"partial-");
            Object[] fixture=fixture(folder); Path log=(Path)fixture[1], logs=log.getParent();
            byte[] bytes=content.getBytes(StandardCharsets.UTF_8); Files.write(log,bytes);
            Path duplicate=logs.resolve("[1]"+IP+"_OTHER-NAME_"+SET+"_2026-01-01.txt");
            byte[] duplicateBytes="old duplicate retained exactly".getBytes(StandardCharsets.UTF_8); Files.write(duplicate,duplicateBytes);
            try(NodeCollectionLease owner=NodeCollectionLease.acquire(logs,IP,()->false)) {
                File fresh=prepare(fixture[0]);
                check(fresh.toPath().equals(log), "ordinary filename remains compatible with validators");
                check(!Files.exists(log), "replacement starts a fresh transcript after retaining old bytes");
            }
            List<Path> archives=archiveFiles(folder);
            check(archives.size()==1 && Arrays.equals(bytes,Files.readAllBytes(archives.get(0))), "partial/invalid bytes retained in unique archive");
            check(Arrays.equals(duplicateBytes,Files.readAllBytes(duplicate)), "existing duplicate stays unchanged");
        }
        Path completed=Files.createTempDirectory(root,"complete-"); Object[] completeFixture=fixture(completed);
        Path completeLog=(Path)completeFixture[1];
        byte[] completeBytes=("<QA-NODE>screen-length 0 temporary\n<QA-NODE>display interface\nInterface data\n<QA-NODE>quit\n").getBytes(StandardCharsets.UTF_8);
        Files.write(completeLog,completeBytes);
        check(BotGetLog_TrueCorp.isLogCompleteForCmdSet(completeLog.toFile(),SET),"fixture validates as completed checkpoint");
        try(NodeCollectionLease owner=NodeCollectionLease.acquire(completeLog.getParent(),IP,()->false)) {
            expectPrepareFailure(completeFixture[0]);
        }
        check(Arrays.equals(completeBytes,Files.readAllBytes(completeLog)),"complete transcript cannot be appended or replaced");
        check(archiveFiles(completed).isEmpty(),"complete transcript not archived");

        Path failed=Files.createTempDirectory(root,"archive-failure-"); Object[] failureFixture=fixture(failed);
        Path failureLog=(Path)failureFixture[1]; byte[] failedBytes="partial must survive archive failure".getBytes(StandardCharsets.UTF_8);
        Files.write(failureLog,failedBytes); Files.write(failed.resolve("Deleted_Log"),new byte[]{1});
        try(NodeCollectionLease owner=NodeCollectionLease.acquire(failureLog.getParent(),IP,()->false)) { expectPrepareFailure(failureFixture[0]); }
        check(Arrays.equals(failedBytes,Files.readAllBytes(failureLog)),"failed archive leaves prior source intact and aborts writer");

        Path collision=root.resolve("collision"); Files.createDirectories(collision);
        Path source=collision.resolve("source.txt"), target=collision.resolve("existing.txt");
        Files.write(source,new byte[]{2,3}); Files.write(target,new byte[]{4,5});
        try { ExistingLogPreservation.moveWithoutReplacement(source,target); throw new AssertionError("collision accepted"); }
        catch(FileAlreadyExistsException expected) { checks++; }
        check(Arrays.equals(new byte[]{2,3},Files.readAllBytes(source)) && Arrays.equals(new byte[]{4,5},Files.readAllBytes(target)), "collision preserves both files");
        Path one=ExistingLogPreservation.archive(source,collision,"same.txt"); Files.write(source,new byte[]{6,7});
        Path two=ExistingLogPreservation.archive(source,collision,"same.txt");
        check(!one.equals(two) && Arrays.equals(new byte[]{2,3},Files.readAllBytes(one)) && Arrays.equals(new byte[]{6,7},Files.readAllBytes(two)), "repeated archive names retain every attempt");

        ExistingLogPreservation.configure(new String[]{ExistingLogPreservation.ARGUMENT,ExistingLogPreservation.DISCARD_ARGUMENT});
        check(ExistingLogPreservation.discardIncomplete(),"explicit discard requires preserved-log mode");
        Path discarded=Files.createTempDirectory(root,"verified-incomplete-");Object[] discardedFixture=fixture(discarded);
        Path discardedLog=(Path)discardedFixture[1];Files.write(discardedLog,"<QA-NODE>screen-length 0 temporary\npartial reply\n".getBytes(StandardCharsets.UTF_8));
        check(Telnet_Multi.isVerifiedIncompleteForRemoval(discardedLog.toFile()),"known readable transcript missing closing command is verified incomplete");
        try(NodeCollectionLease owner=NodeCollectionLease.acquire(discardedLog.getParent(),IP,()->false)){prepare(discardedFixture[0]);}
        check(!Files.exists(discardedLog) && archiveFiles(discarded).isEmpty(),"only verified incomplete file is removed when explicitly enabled");
        check(!Telnet_Multi.moveLogToArchiveIfInactive(completeLog.toFile(),"retry"),"completed file protected centrally with discard enabled");
        check(Arrays.equals(completeBytes,Files.readAllBytes(completeLog)),"discard mode retains completed bytes");
        Path uncertain=Files.createTempDirectory(root,"uncertain-");Object[] uncertainFixture=fixture(uncertain);Path uncertainLog=(Path)uncertainFixture[1];
        byte[] uncertainBytes="INVALID HEAD even with known cmdSet".getBytes(StandardCharsets.UTF_8);Files.write(uncertainLog,uncertainBytes);
        check(!Telnet_Multi.isVerifiedIncompleteForRemoval(uncertainLog.toFile()),"malformed identity is unknown, not incomplete");
        try(NodeCollectionLease owner=NodeCollectionLease.acquire(uncertainLog.getParent(),IP,()->false)){prepare(uncertainFixture[0]);}
        check(Arrays.equals(uncertainBytes,Files.readAllBytes(archiveFiles(uncertain).get(0))),"uncertain file retained in archive under discard mode");
        Path unrecognized=collision.resolve("[1]"+IP+"_QA-NODE_UNKNOWN-SET_2026-11-01.txt");Files.write(unrecognized,"<QA-NODE>screen-length 0 temporary\n".getBytes(StandardCharsets.UTF_8));
        check(!Telnet_Multi.isVerifiedIncompleteForRemoval(unrecognized.toFile()),"unrecognized cmdSet cannot authorize removal");
        Path badUtf8=collision.resolve("[1]"+IP+"_QA-NODE_"+SET+"_2026-11-01.txt");Files.write(badUtf8,new byte[]{(byte)0xc3,(byte)0x28});
        check(!Telnet_Multi.isVerifiedIncompleteForRemoval(badUtf8.toFile()),"decoding error cannot authorize removal");
        check(!Telnet_Multi.isVerifiedIncompleteForRemoval(collision.resolve("missing.txt").toFile()),"read failure cannot authorize removal");
        check(!Telnet_Multi.isVerifiedIncompleteForRemoval(collision.toFile()),"nonregular file cannot authorize removal");

        Telnet_Multi.setBackgroundMonitorsSuppressed(false); // explicit mode itself must protect direct constructors
        Telnet_Multi.startWrongVendorMonitor(null); Telnet_Multi.startCommandCompletionMonitor(null); Telnet_Multi.startConnectionFailMonitor(null);
        for(String field:new String[]{"wrongVendorMonitorThread","commandMonitorThread","connFailMonitorThread"}) {
            Field f=Telnet_Multi.class.getDeclaredField(field); f.setAccessible(true); check(f.get(null)==null,"global repair monitor suppressed: "+field);
        }
        ProcessBuilder child=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),ExistingLogPreservationRegression.class.getName(),"environment");
        child.environment().put(ExistingLogPreservation.ENVIRONMENT,"1"); check(child.inheritIO().start().waitFor()==0,"preservation inherited by child process");
        ExistingLogPreservation.configure(new String[0]); Telnet_Multi.setBackgroundMonitorsSuppressed(false);
        System.out.println("PASS ExistingLogPreservationRegression checks="+checks);
    }
    private static List<Path> archiveFiles(Path root) throws IOException {
        List<Path> found=new ArrayList<>(); Path dir=root.resolve("Deleted_Log");
        if(Files.isDirectory(dir))try(java.util.stream.Stream<Path> paths=Files.list(dir)){paths.filter(Files::isRegularFile).forEach(found::add);} return found;
    }
    private static Object[] fixture(Path root) throws Exception {
        Path logs=root.resolve("Total_Log");Files.createDirectories(logs);
        Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);Unsafe allocator=(Unsafe)f.get(null);
        Telnet_Multi collector=(Telnet_Multi)allocator.allocateInstance(Telnet_Multi.class);
        PathFile paths=(PathFile)allocator.allocateInstance(PathFile.class);
        set(paths,"Log",logs.toString());set(collector,"FileInput",paths);set(collector,"runtimeDeviceName","");
        return new Object[]{collector,logs.resolve("[1]"+IP+"_"+DEVICE+"_"+SET+"_"+CollectionCycle.currentDay()+".txt")};
    }
    private static File prepare(Object collector) throws Exception {
        Method m=Telnet_Multi.class.getDeclaredMethod("prepareFreshLogFile",String.class,String.class,String.class,int.class);m.setAccessible(true);
        return (File)m.invoke(collector,IP,DEVICE,SET,1);
    }
    private static void expectPrepareFailure(Object collector) throws Exception {
        try { prepare(collector);throw new AssertionError("unsafe writer admitted"); }
        catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalStateException,"unsafe writer fails closed");}
    }
    private static void set(Object target,String name,Object value) throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    @SuppressWarnings("unchecked") private static void cacheCommands() throws Exception {
        Field initialized=BotGetLog_TrueCorp.class.getDeclaredField("commandCacheInitialized");initialized.setAccessible(true);initialized.set(null,true);
        for(String[] setting:new String[][]{{"CMDSET_FIRST_COMMAND_CACHE","screen-length 0 temporary"},{"CMDSET_LAST_COMMAND_CACHE","quit"}}){
            Field f=BotGetLog_TrueCorp.class.getDeclaredField(setting[0]);f.setAccessible(true);((Map<String,String>)f.get(null)).put(SET.toLowerCase(Locale.ROOT),setting[1]);
        }
    }
    private static TrueLinkOpticalAutoMode.Selection selection() throws Exception {
        Constructor<?> c=TrueLinkOpticalAutoMode.Selection.class.getDeclaredConstructor(boolean.class,boolean.class,boolean.class,String.class,LinkedHashMap.class,List.class);c.setAccessible(true);
        LinkedHashMap<Integer,List<String>> rows=new LinkedHashMap<>();rows.put(1,Arrays.asList(SET));
        return (TrueLinkOpticalAutoMode.Selection)c.newInstance(true,false,false,"#1",rows,Arrays.asList(DEVICE));
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
