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
        annotatedStatesAndQuotes();
        flattenedDescriptionWraps();
        System.out.println("PASS Huawei ARP: field spacing, interface joins, annotated states, quoted/comma/wrapped/empty descriptions, terminal wrap padding");
    }

    static void annotatedStatesAndQuotes() throws Exception {
        String[][] cases = {
            {"up(E)", "down", "\"Example:V100(Backup)\""},
            {"up", "up(s)", "Service \"Primary\", site A"},
            {"*down", "down", "Administratively disabled"},
            {"down", "down", ""},
            {"up", "up(s)", ""},
            {"up(E)", "down", "Example:V200"},
            {"up", "up", "Wrapped description continued with \"quotes\", and comma"},
            {"^down", "down(s)", "Annotated state"}
        };
        StringBuilder log = new StringBuilder("<AN-TEST>display arp all\n");
        for (int i = 0; i < cases.length; i++) {
            log.append("192.0.2.").append(i + 1)
               .append(" 0011-2233-4455 I - Eth-Trunk348.").append(2001 + i).append(" VPN\n");
        }
        log.append("Total:8\n<AN-TEST>display interface description\n")
           .append("*down: administratively down\n(E): E-Trunk down\n(s): spoofing\n")
           .append("Interface PHY Protocol Description\n");
        for (int i = 0; i < cases.length; i++) {
            log.append("Eth-Trunk348.").append(2001 + i).append(" ")
               .append(cases[i][0]).append(" ").append(cases[i][1]);
            if (!cases[i][2].isEmpty()) log.append(" ").append(i == 6
                    ? "Wrapped description\n                                             continued with \"quotes\", and comma"
                    : cases[i][2]);
            log.append("\n");
        }
        log.append("  <AN-TEST>\n");
        String csv = CheckARP.Sub(new BufferedReader(new StringReader(log.toString())),
                "[1]192.0.2.254_AN-TEST_HW-ARP_2026-09-11");
        String[] lines = csv.trim().split("\n");
        if (lines.length != cases.length) throw new AssertionError("Incorrect row count: " + csv);
        for (int i = 0; i < cases.length; i++) {
            String suffix = ",VPN," + cases[i][0] + "," + cases[i][1] + ",\""
                    + cases[i][2].replace("\"", "\"\"") + "\"";
            if (!lines[i].endsWith(suffix)) throw new AssertionError("Expected " + suffix + " in " + lines[i]);
        }
    }

    static String spaces(int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, ' ');
        return new String(chars);
    }

    static void flattenedDescriptionWraps() throws Exception {
        String[] descriptions = {
            "To_CN-MTG-1_HWNE5KE_10G_1/1/3_10.207.0.21_TLR01531",
            "To_RNC-CMI8362X_Ericsson_Iub(CP/UP)_CAX-A",
            "\"Test_LTE850_Nodeb_CMI6743_BKFT_Vlan3303\"",
            "\"To_Huawei_BSC-CMI7201Q_A interface_(CP)_RFT1_N/A_072013001A\"",
            "HUAWEI, GigabitEthernet4/0/0.2001 Interface",
            "*temp link  To_RN-CMI1000-1_CMICMI5401M_HWCX16A_10G_2/1/23_10.163.10.101*",
            "DPI Phase 3 to SW-Extreme",
            "Service  \"Primary\", site A",
            "",
            "12345678901234567890123456789012 " + "word after a real space",
            "123456789012345678901234567890123" + " word starts on the next terminal line"
        };
        for (int descriptionColumn : new int[]{47, 55}) {
            StringBuilder log = new StringBuilder("<AN-TEST>display arp all\n");
            for (int i = 0; i < descriptions.length + 2; i++) {
                log.append("192.0.2.").append(i + 1)
                   .append(" 0011-2233-4455 I - GE1/0/").append(i).append(" VPN\n");
            }
            log.append("Total:").append(descriptions.length + 2)
               .append("\n<AN-TEST>display interface description\n")
               .append(String.format("%-30s%-8s", "Interface", "PHY"))
               .append(String.format("%-" + (descriptionColumn - 38) + "s", "Protocol"))
               .append("Description\n");
            for (int i = 0; i < descriptions.length; i++) {
                log.append(String.format("%-30s%-8s", "GE1/0/" + i, "*down"))
                   .append(String.format("%-" + (descriptionColumn - 38) + "s", "down"));
                String desc = descriptions[i];
                int fragmentWidth = 80 - descriptionColumn;
                for (int offset = 0; offset < desc.length(); offset += fragmentWidth) {
                    if (offset > 0) log.append(spaces(descriptionColumn));
                    log.append(desc, offset, Math.min(offset + fragmentWidth, desc.length()));
                }
                log.append('\n');
            }
            // Wide intentional spacing away from a wrap and a too-short gap
            // at column 80 must survive unchanged.
            String[] literal = {"A" + spaces(descriptionColumn) + "B",
                "12345678901234567890123456789012345678901234567890123456789012345678901234567890"
                    .substring(0, 80 - descriptionColumn) + spaces(descriptionColumn - 1) + "B"};
            for (int i = 0; i < literal.length; i++) {
                log.append(String.format("%-30s%-8s", "GE1/0/" + (descriptions.length + i), "up"))
                   .append(String.format("%-" + (descriptionColumn - 38) + "s", "up"))
                   .append(literal[i]).append('\n');
            }
            log.append("<AN-TEST>\n");
            String[] rows = CheckARP.Sub(new BufferedReader(new StringReader(log.toString())),
                    "[1]192.0.2.254_AN-TEST_HW-ARP_2026-09-11").trim().split("\n");
            if (rows.length != descriptions.length + literal.length) throw new AssertionError("Missing ARP rows");
            for (int i = 0; i < rows.length; i++) {
                String expected = i < descriptions.length ? descriptions[i] : literal[i - descriptions.length];
                String states = i < descriptions.length ? ",VPN,*down,down," : ",VPN,up,up,";
                String suffix = states + "\"" + expected.replace("\"", "\"\"") + "\"";
                if (!rows[i].endsWith(suffix)) throw new AssertionError("Column " + descriptionColumn
                        + " expected " + suffix + " in " + rows[i]);
            }
        }
    }
}
