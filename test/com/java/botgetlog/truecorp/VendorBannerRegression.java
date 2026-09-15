package com.java.botgetlog.truecorp;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import sun.misc.Unsafe;

/** Offline replay of banner framing, real prompt families and failed completion. */
public final class VendorBannerRegression {
    private static final String BANNER = "\r\n##################################################################\r\n"
            + "#                         W A R N I N G                            #\r\n"
            + "# Unauthorized access is forbidden.                              #\r\n"
            + "##################################################################\r\n";
    private static final Unsafe UNSAFE;
    static {
        try { Field f=Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); UNSAFE=(Unsafe)f.get(null); }
        catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static void field(Object o,String name,Object value)throws Exception {
        Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);
    }
    private static Object call(Object target,String name,Class<?>[] types,Object... values)throws Exception {
        Method m=Telnet_Multi.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,values);
    }
    private static void equal(Object expected,Object actual) {
        if(!expected.equals(actual))throw new AssertionError("Expected "+expected+", got "+actual);
    }
    private static Telnet_Multi fixture(Path dir,String input)throws Exception {
        Telnet_Multi self=(Telnet_Multi)UNSAFE.allocateInstance(Telnet_Multi.class);
        PathFile paths=(PathFile)UNSAFE.allocateInstance(PathFile.class);
        field(paths,"LogWork",dir.toString()+File.separator);
        field(self,"FileInput",paths);field(self,"formattedDateTimeLOG","fixture");
        field(self,"in",new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        field(self,"out",new PrintStream(new ByteArrayOutputStream()));
        return self;
    }
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("vendor-banner-regression-");
        for(String token:new String[]{"#","########",">","]","***#","# WARNING #"}) {
            equal(false,call(null,"hasInteractivePromptToken",new Class<?>[]{String.class},token));
            for(String vendor:new String[]{"HW","N","ZTE"})
                equal(vendor,Telnet_Multi.detectVendorFromPrompt(token,vendor,false));
        }
        String[][] cases={
            {"HW","<CPE-HUAWEI>"},{"HW","[~CPE-HUAWEI]"},{"HW","[*CPE-HUAWEI-GigabitEthernet0/2/0]"},
            {"ZTE","CPE-ZTE#"},{"ZTE","CPE-ZTE>"},{"ZTE","CPE-ZTE(config-if)#"},
            {"N","A:CPE-NOKIA#"},{"N","*B:CPE-NOKIA#"},{"N","A:CPE-NOKIA-(SITE)#"},
            {"N","*A:CPE-NOKIA>config>router#"}
        };
        for(String[] row:cases) {
            String vendor=row[0],prompt=row[1],input=BANNER+prompt;
            equal(true,call(null,"hasInteractivePromptToken",new Class<?>[]{String.class},prompt));
            equal(vendor,Telnet_Multi.detectVendorFromPrompt(input,"ZTE",false));
            Telnet_Multi self=fixture(dir,input);
            equal(input,self.readUntilAny(">","#","]"));
            equal(input,call(null,"readSshBootstrapResponse",new Class<?>[]{InputStream.class,PrintStream.class,int.class,String[].class},
                    new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),new PrintStream(new ByteArrayOutputStream()),1000,new String[]{">","#","]"}));
            Path log=dir.resolve("vendor-"+UUID.randomUUID()+".txt");
            Files.write(log,(input+"display version\n").getBytes(StandardCharsets.UTF_8));
            equal(vendor+"-LLDP-Link_OPTIC",Telnet_Multi.adjustCmdSetVendorFromLog(log.toFile(),"ZTE-LLDP-Link_OPTIC"));
        }
        for(String vendor:new String[]{"HW","N","ZTE"}) {
            Path log=dir.resolve(vendor+"-rejected.txt");
            Files.write(log,(BANNER+"<NODE>show version\nError: Unrecognized command found at '^' position.\n<NODE>quit\nScript done\n").getBytes(StandardCharsets.UTF_8));
            Telnet_Multi self=fixture(dir,"");
            equal(false,call(self,"validateCollectedCommands",new Class<?>[]{File.class,int.class,String.class,String.class,String.class},log.toFile(),1234,"192.0.2.10","CPE-HUAWEI",vendor+"-LLDP-Link_OPTIC"));
            equal(true,self.hasSessionFailureRecorded());
            Files.write(log,"<NODE>display version\nVRP software\n<NODE>quit\n".getBytes(StandardCharsets.UTF_8));
            equal(true,call(fixture(dir,""),"validateCollectedCommands",new Class<?>[]{File.class,int.class,String.class,String.class,String.class},log.toFile(),1234,"192.0.2.10","CPE-HUAWEI",vendor+"-LLDP-Link_OPTIC"));
        }
        if(args.length>0) {
            File saved=new File(args[0]);
            equal("HW-LLDP-Link_OPTIC",Telnet_Multi.adjustCmdSetVendorFromLog(saved,"ZTE-LLDP-Link_OPTIC"));
            equal(false,call(fixture(dir,""),"validateCollectedCommands",new Class<?>[]{File.class,int.class,String.class,String.class,String.class},saved,1234,"192.0.2.10","CPE-HUAWEI","ZTE-LLDP-Link_OPTIC"));
        }
        System.out.println("PASS VendorBannerRegression: 10 prompt forms, SSH/Telnet streaming, banner-only fallback, saved logs and failed completion");
    }
}
