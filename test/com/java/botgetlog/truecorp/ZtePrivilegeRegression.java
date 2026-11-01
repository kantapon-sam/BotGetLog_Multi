package com.java.botgetlog.truecorp;

import com.java.shared.DeviceEnableSettings;
import com.java.shared.ZteEnableFlow;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import sun.misc.Unsafe;

/** Actual node-login/enable readers with synthetic credentials; no network. */
public final class ZtePrivilegeRegression {
    private static int checks;
    private static final String SETTING = "botgetlog.enable.settingsDir";
    private static final String CMD = "ZTE-LLDP-Link_OPTIC";
    private static final class Quiet extends Telnet_Multi {
        private Quiet() { super("", "", "", "", "", "", "", "", 0, "", ""); }
        @Override public void logwork(String ignored) { }
        @Override public void Connection_failed(int row, String ip, String node, String set, String reason) { }
        @Override public void disconnect() { }
    }
    public static void main(String[] args) throws Exception {
        String previous = System.getProperty(SETTING);
        Path root = Files.createTempDirectory("zte-privilege-");
        try {
            System.setProperty(SETTING, root.toString());
            DeviceEnableSettings settings = new DeviceEnableSettings(root);
            Map<String,String> entry = new HashMap<>();entry.put("password", "synthetic-enable");
            settings.save("ZTE", "missing", Collections.singletonList(entry), 1, false);
            loginThenEnable(false, ">", "Password:\r\nQA-NODE#", true, "enable\nsynthetic-enable\n");
            loginThenEnable(true, ">", "Password:\r\nQA-NODE#", true, "enable\nsynthetic-enable\n");
            loginThenEnable(true, "#", "Password:\r\nQA-NODE#", true, "enable\nsynthetic-enable\n");
            loginThenEnable(false, "#", "", true, "");
            loginThenEnable(true, "#", "QA-NODE#", true, "enable\n");
            loginThenEnable(true, "#", "OTHER-NODE#", false, "enable\n");
            loginThenEnable(true, "#", "%Error: Invalid command\r\nQA-NODE#", false, "enable\n");
            loginThenEnable(true, "#", "Password:\r\nIncorrect password\r\nQA-NODE#", false, "enable\nsynthetic-enable\n");
            loginThenEnable(true, ">", "Password:\r\nOTHER-NODE#", false, "enable\nsynthetic-enable\n");
            loginThenEnable(false, ">", "Account locked\r\nPassword:", false, "enable\n");
            loginThenEnable(false, ">", "Password:\r\nIncorrect password\r\nQA-NODE>", false, "enable\nsynthetic-enable\n");
            Object[] stale = fixture("QA-NODE#");
            set(stale[0], "s", "QA-NODE#");
            invoke(stale[0], "updateRuntimeDeviceNameFromResponse", new Class[]{String.class,String.class,String.class}, "QA-NODE>","QA-NODE",CMD);
            check(Boolean.TRUE.equals(enable(stale[0])), "fresh user prompt replaces prior privileged state");
            check(writes(stale).equals("enable\n"), "stale state cannot bypass enable");
            Object[] history = fixture("");set(history[0], "s", "");set(history[0], "LOG", new StringBuilder("QA-NODE#"));
            check(Boolean.FALSE.equals(enable(history[0])) && writes(history).isEmpty(), "historical transcript is not current privilege");
            Method state = Telnet_Multi.class.getDeclaredMethod("zteEnableState",String.class,String.class);state.setAccessible(true);
            for (String bad : Arrays.asList("[TIMEOUT-READ]", "Connection closed by foreign host", "OTHER-NODE#", "#", "QA-NODE(config)#"))
                check(state.invoke(null,bad,"QA-NODE")==ZteEnableFlow.State.FAILED,"reject timeout/foreign/invalid prompt: "+bad);
            check(state.invoke(null,"\u001b[32mQA-NODE#\u001b[0m","QA-NODE")==ZteEnableFlow.State.PRIVILEGED,"ANSI same-node prompt");
            for(String secret:Arrays.asList("QA","wrong-password")) {
                entry.put("password",secret);
                settings.save("ZTE",(String)settings.status().get("revision"),Collections.singletonList(entry),1,false);
                loginThenEnable(true,"#","Password:\r\n"+secret+"\r\nQA-NODE#",true,"enable\n"+secret+"\n");
            }
            System.setProperty(SETTING, root.resolve("empty").toString());
            loginThenEnable(false, ">", "QA-NODE#", true, "enable\n");
            loginThenEnable(true, "#", "Password:", false, "enable\n");
            System.out.println("PASS ZtePrivilegeRegression checks="+checks);
        } finally {
            if(previous==null)System.clearProperty(SETTING);else System.setProperty(SETTING,previous);
            try(java.util.stream.Stream<Path> paths=Files.walk(root)) {
                for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);
            }
        }
    }
    private static void loginThenEnable(boolean backup,String suffix,String enableResponse,boolean expected,String sent) throws Exception {
        Object[] fixture=fixture("Password:\r\nQA-NODE"+suffix+"\r\n"+enableResponse);
        Object collector=fixture[0];String response;
        if(backup) {
            response=(String)invoke(collector,"loginCllsBackup",new Class[]{CllsFallback.Credential.class,String.class},
                    new CllsFallback.Credential(1,"synthetic-backup","synthetic-login"),"Authentication failed\r\nUsername:");
        } else {
            response=(String)invoke(collector,"completeSshNodeLogin",new Class[]{String.class,String.class,String.class,String.class,String.class,String.class},
                    "192.0.2.1","QA-NODE",CMD,"Username:","clls","synthetic-login");
        }
        set(collector,"nodeUsesBackupCredentials",backup);
        invoke(collector,"updateRuntimeDeviceNameFromResponse",new Class[]{String.class,String.class,String.class},response,"QA-NODE",CMD);
        ((ByteArrayOutputStream)fixture[1]).reset();
        check(Boolean.valueOf(expected).equals(enable(collector)),"privilege result primary/backup="+backup+" prompt="+suffix);
        check(sent.equals(writes(fixture)),"only expected enable/password writes");
        check(!get(collector,"LOG").toString().contains("synthetic-enable"),"enable secret excluded from transcript");
    }
    private static Object enable(Object c)throws Exception{return invoke(c,"ensureZtePrivilegedMode",new Class[]{String.class,String.class,String.class,int.class},CMD,"192.0.2.1","QA-NODE",1);}
    private static Object[] fixture(String input)throws Exception {
        Field field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        Object c=((Unsafe)field.get(null)).allocateInstance(Quiet.class);
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        set(c,"in",new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read(byte[] data,int start,int length){return super.read(data,start,Math.min(1,length));}
        });
        set(c,"out",new PrintStream(output,true,"UTF-8"));set(c,"LOG",new StringBuilder());set(c,"Loopback","192.0.2.1");
        return new Object[]{c,output};
    }
    private static String writes(Object[] f){return f[1].toString().replace("\r","");}
    private static Object invoke(Object c,String name,Class<?>[] types,Object...args)throws Exception{Method m=Telnet_Multi.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(c,args);}
    private static void set(Object c,String name,Object value)throws Exception{Field f=Telnet_Multi.class.getDeclaredField(name);f.setAccessible(true);f.set(c,value);}
    private static Object get(Object c,String name)throws Exception{Field f=Telnet_Multi.class.getDeclaredField(name);f.setAccessible(true);return f.get(c);}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
