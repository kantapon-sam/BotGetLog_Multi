package com.java.tools.arp;
import java.io.*;
import java.lang.reflect.Method;
import java.util.Arrays;
public final class CheckARPHuaweiRegression {
    static final Method PARSE;
    static {try{PARSE=CheckARP.class.getDeclaredMethod("parseHuaweiArpLine",String.class);PARSE.setAccessible(true);}catch(Exception e){throw new RuntimeException(e);}}
    static void row(String source,String...expected)throws Exception{
        String[] actual=(String[])PARSE.invoke(null,source);
        if(!Arrays.equals(expected,actual))throw new AssertionError(source+" => "+Arrays.toString(actual));
    }
    public static void main(String[] args)throws Exception{
        row("192.0.2.85   0011-2233-4401            I -         Eth-Trunk302.2002 full-VPN-100","192.0.2.85","0011-2233-4401","","I -","Eth-Trunk302.2002","full-VPN-100");
        row("198.51.100.67  0011-2233-4402  14        D-0         Eth-Trunk302.2027 full-VPN-200","198.51.100.67","0011-2233-4402","14","D-0","Eth-Trunk302.2027","full-VPN-200");
        row("192.168.100.100 0011-2233-4455 3 D-1 GE1/1/22.3180 full-VPN","192.168.100.100","0011-2233-4455","3","D-1","GE1/1/22.3180","full-VPN");
        row("192.0.2.1\t0011-2233-4455\tI\t-\tGlobal-VE1.3333\tMPLS-VPN","192.0.2.1","0011-2233-4455","","I -","Global-VE1.3333","MPLS-VPN");
        row("192.0.2.2 0011-2233-4455 12 D-0 GE1/0/1","192.0.2.2","0011-2233-4455","12","D-0","GE1/0/1","");
        row("192.0.2.3 0011-2233-4455 S-- Vlanif100 --","192.0.2.3","0011-2233-4455","","S--","Vlanif100","--");
        for(String invalid:new String[]{"IP ADDRESS MAC ADDRESS EXPIRE(M) TYPE INTERFACE VPN-INSTANCE"," 3180/-","-----","","10.0.0.1 incomplete"})if(PARSE.invoke(null,invalid)!=null)throw new AssertionError("Accepted non-row: "+invalid);
        String log="<AN-TEST>display version\nHUAWEI CX600-X16A uptime is 2 days\n<AN-TEST>display arp all\n"
          +"192.0.2.85   0011-2233-4401            I -         Eth-Trunk302.2002 full-VPN-100\n"
          +"198.51.100.67  0011-2233-4402  14        D-0         Eth-Trunk302.2027 full-VPN-200\n"
          +"192.0.2.2 0011-2233-4455 12 D-0 Global-VE1.3333 MPLS-VPN\nTotal:3\n"
          +"<AN-TEST>display interface description\nInterface PHY Protocol Description\n"
          +"Eth-Trunk302.2002             up      up       Example:site-A\n"
          +"Eth-Trunk302.2027             up      down     Example:site-B\n"
          +"Global-VE1.3333              up      up       Service, with comma\n<AN-TEST>\n";
        String result=CheckARP.Sub(new BufferedReader(new StringReader(log)),"[1]192.0.2.254_AN-TEST_HW-ARP_2026-09-11");
        for(String expected:new String[]{",I -,Eth-Trunk302.2002,full-VPN-100,up,up,\"Example:site-A\"",",D-0,Eth-Trunk302.2027,full-VPN-200,up,down,\"Example:site-B\"",",D-0,Global-VE1.3333,MPLS-VPN,up,up,\"Service, with comma\""})if(!result.contains(expected))throw new AssertionError("Missing "+expected+" in "+result);
        System.out.println("PASS Huawei ARP: field spacing, long IPv4, missing expiry/VPN, static/dynamic types, interface joins and CSV descriptions");
    }
}
