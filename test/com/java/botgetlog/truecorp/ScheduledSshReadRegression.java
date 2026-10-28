package com.java.botgetlog.truecorp;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import sun.misc.Unsafe;

/** Scheduled collector stream tests: no network, workbook or production state. */
public final class ScheduledSshReadRegression {
    private static final String PROMPT = "<QA-HW>";
    private static int checks;
    private static final Method READ;
    private static final Unsafe ALLOCATOR;
    static {
        try {
            Field unsafe = Unsafe.class.getDeclaredField("theUnsafe"); unsafe.setAccessible(true);
            ALLOCATOR = (Unsafe) unsafe.get(null);
            READ = Telnet_Multi.class.getDeclaredMethod("readStreamToFileSsh", BufferedOutputStream.class,
                    List.class, long.class, long.class, long.class, String.class, String.class, boolean.class);
            READ.setAccessible(true);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static final class Input extends ByteArrayInputStream {
        final int chunk; long singles, blocks;
        Input(String text, int chunk) { super(text.getBytes(StandardCharsets.ISO_8859_1)); this.chunk=chunk; }
        @Override public synchronized int available() { return Math.min(super.available(), chunk); }
        @Override public synchronized int read() { singles++; return super.read(); }
        @Override public synchronized int read(byte[] b,int off,int len) { blocks++; return super.read(b,off,Math.min(len,chunk)); }
    }
    private static final class Result {
        Object state; byte[] saved; String writes; long singles, blocks;
        boolean flag(String name) throws Exception {
            Field f=state.getClass().getDeclaredField(name); f.setAccessible(true); return f.getBoolean(state);
        }
    }
    private static void set(Object target,String name,Object value) throws Exception {
        Field f=Telnet_Multi.class.getDeclaredField(name); f.setAccessible(true); f.set(target,value);
    }
    private static Result read(String text,int chunk,String node,String prompt,boolean exit) throws Exception {
        Object collector=ALLOCATOR.allocateInstance(Telnet_Multi.class);
        Input input=new Input(text,chunk); ByteArrayOutputStream saved=new ByteArrayOutputStream(),writes=new ByteArrayOutputStream();
        set(collector,"in",input); set(collector,"out",new PrintStream(writes,true,"UTF-8"));
        Result r=new Result();
        try(BufferedOutputStream stream=new BufferedOutputStream(saved)) {
            r.state=READ.invoke(collector,stream,Arrays.asList(prompt),System.currentTimeMillis(),3000L,180L,"[fixture]",node,exit);
        }
        r.saved=saved.toByteArray();r.writes=writes.toString("UTF-8");r.singles=input.singles;r.blocks=input.blocks;return r;
    }
    private static void check(boolean condition,String label) { checks++;if(!condition)throw new AssertionError(label); }
    private static String repeat(String text,int count) {
        StringBuilder b=new StringBuilder(text.length()*count);for(int i=0;i<count;i++)b.append(text);return b.toString();
    }
    private static void complete(String text,int chunk,String node,String prompt) throws Exception {
        Result r=read(text,chunk,node,prompt,false);
        check(r.flag("promptDetected"),"prompt missing: "+node+" chunk="+chunk);
        check(Arrays.equals(text.getBytes(StandardCharsets.ISO_8859_1),r.saved),"raw bytes changed");
        check(r.singles==0,"single-byte read");
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("botgetlog.command.promptSettle.ms","100");
        for(String[] pair:new String[][]{{"QA-HW",PROMPT},{"QA-HW","HRP_M<QA-HW>"},{"QA-HW","HRP_S<QA-HW>"},
                {"QA-NOKIA","*A:QA-NOKIA#"},{"QA-ZTE","QA-ZTE#"},{"QA-JUNOS","user@QA-JUNOS>"}}) {
            for(int chunk:new int[]{1,7,8192}) {
                complete("display interface\r\nDescription: Tie#\r\nDescription: <OTHER-NODE>\r\nraw \u00ff\u0080\u00e9\r\nEND\r\n"+pair[1]+"\r\n",chunk,pair[0],pair[1]);
            }
        }
        for(int padding:new int[]{4092,8188,8191,8192})
            complete(repeat("x",padding)+"\r\nEND\r\n"+PROMPT+"\r\n",8192,"QA-HW",PROMPT);
        for(String marker:new String[]{"--more--","MORE?","---- more ----","---(more 50%)---","--more (Press Space)--"}) {
            for(int chunk:new int[]{1,7,8192}) {
                String text=repeat("x",8188)+"\r\n"+marker+"\b\b\r\nSECOND PAGE\r\n"+PROMPT+"\r\n";
                Result r=read(text,chunk,"QA-HW",PROMPT,false);
                check(r.flag("promptDetected"),"paged prompt missing");check(r.writes.equals(" "),"page space count");
                check(Arrays.equals(text.getBytes(StandardCharsets.ISO_8859_1),r.saved),"pagination log changed");
            }
        }
        complete("more\r\n"+repeat("x",17000)+"\r\n----\r\nEND\r\n"+PROMPT,8192,"QA-HW",PROMPT);
        for(String tail:new String[]{"", "<OTHER-NODE>", "DescriptionEnding#", "<QA-HW> continued output"}) {
            Result r=read("partial output\r\n"+tail,7,"QA-HW",PROMPT,false);
            check(!r.flag("promptDetected")&&r.flag("timedOut"),"accepted incomplete/wrong prompt");
        }
        for(String close:new String[]{"Connection closed by foreign host","Connection reset","Connection timed out",
                "Enter IP address [press q/Q to quit]:"}) {
            for(int chunk:new int[]{1,7,8192}) {
                Result r=read("partial data\r\n"+close+"\r\n"+repeat("remaining data\r\n",700),chunk,"QA-HW",PROMPT,false);
                check(r.flag("remoteClosed")&&!r.flag("promptDetected"),"missed close: "+close);
            }
        }
        Result exit=read("quit\r\nConnection closed by foreign host\r\n",8192,"QA-HW",PROMPT,true);
        check(exit.flag("remoteClosed"),"exit-close must still be validated by caller");
        check(read("Connection reset"+repeat("x",8000),8192,"QA-HW",PROMPT,false).flag("remoteClosed"),"close evicted inside long block");
        ExecutorService pool=Executors.newFixedThreadPool(20);
        String data=repeat("GE0/0/1 current state : UP\r\nDescription: To-CORE; packets 12345678\r\n",4000)+"END\r\n"+PROMPT;
        long start=System.nanoTime();long blocks=0;
        try {
            List<Future<Result>> futures=new ArrayList<>();
            for(int i=0;i<20;i++)futures.add(pool.submit(()->read(data,8192,"QA-HW",PROMPT,false)));
            for(Future<Result> future:futures) {
                Result r=future.get(60,TimeUnit.SECONDS);check(r.flag("promptDetected"),"concurrent prompt missing");
                check(Arrays.equals(data.getBytes(StandardCharsets.ISO_8859_1),r.saved),"concurrent bytes changed");
                check(r.singles==0&&r.blocks<=data.length()/8192+1,"concurrent block reads");blocks+=r.blocks;
            }
        } finally {pool.shutdownNow();}
        System.out.printf(Locale.ROOT,"SCHEDULED_SSH_READ_OK checks=%d nodes=20 bytesPerNode=%d wallMs=%.2f blockReads=%d%n",checks,data.length(),(System.nanoTime()-start)/1e6,blocks);
    }
}
