package com.java.botgetlog.truecorp;
import java.lang.reflect.*;
import sun.misc.Unsafe;

/** No devices or network: exercise the actual collector filename/reuse paths. */
public class CollectionCycleLogRegression {
    static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        System.setProperty("true.collection.day","2026-10-26");
        Method current=Telnet_Multi.class.getDeclaredMethod("currentDailyLogDateTag");current.setAccessible(true);
        check(current.invoke(null).equals("2026-10-26"),"assigned cycle used for log reuse");
        Field field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        Telnet_Multi session=(Telnet_Multi)((Unsafe)field.get(null)).allocateInstance(Telnet_Multi.class);
        Method filename=Telnet_Multi.class.getDeclaredMethod("buildDailyLogFileNameForDeviceName",String.class,String.class,String.class,int.class,String.class);
        filename.setAccessible(true);
        String name=(String)filename.invoke(session,"10.1.1.1","CPE-TEST","HW-LLDP-Link_OPTIC",1,"");
        check(name.equals("[1]10.1.1.1_CPE-TEST_HW-LLDP-Link_OPTIC_2026-10-26.txt"),"filename owns cycle, not wall clock date");
        Method active=Telnet_Multi.class.getDeclaredMethod("resolveActiveLogDateTag",String.class,String.class,String.class,int.class);active.setAccessible(true);
        check(active.invoke(session,"10.1.1.1","CPE-TEST","HW-LLDP-Link_OPTIC",1).equals("2026-10-26"),"new session uses cycle");
        Method fresh=BotGetLog_TrueCorp.class.getDeclaredMethod("isLogFileForToday",String.class);fresh.setAccessible(true);
        check(Boolean.TRUE.equals(fresh.invoke(null,name)),"same cycle accepted");
        check(Boolean.FALSE.equals(fresh.invoke(null,name.replace("2026-10-26","2026-10-25"))),"prior cycle rejected");
        System.setProperty("true.collection.day","2026-10-27");
        check(current.invoke(null).equals("2026-10-26"),"running JVM stays pinned");
        System.out.println("Collector cycle filenames, freshness and session pinning passed.");
    }
}
