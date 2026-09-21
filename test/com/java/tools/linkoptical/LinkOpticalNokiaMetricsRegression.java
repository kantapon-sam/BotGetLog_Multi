package com.java.tools.linkoptical;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class LinkOpticalNokiaMetricsRegression {
    public static void main(String[] args) throws Exception {
        String flagged = "Tx Output Power (dBm) 0.70 3.00 2.00 -2.00 -3.00\n"
                + "Rx Optical Power (avg dBm) -17.03 3.00 2.00 -17.01! -20.00\n";
        String previous = "Rx Optical Power (avg dBm) -5.81 3.00 2.00 -21.02 -24.00\n";
        String log = port("1/1/17", "1310 nm", previous)
                + port("1/1/25", "1307.500 nm", flagged)
                + port("1/1/26", "1547.500 nm", "")
                + port("1/1/27", "1561.419 nm", "Rx Optical Power (avg dBm) +1.25* +3.00! +2.00! -17.01 -20.00\n")
                + port("1/1/28", "1550 nm", "Rx Optical Power (avg dBm) -5.00 N/A N/A N/A N/A\n")
                + port("1/1/29", "1310 nm", "Rx Optical Power (avg dBm) N/A 3.00 2.00 -21.00 -24.00\n");
        Map<String,String[]> rows = parse(log);
        expect(rows,"1/1/25",12,"1307.500 nm");
        expect(rows,"1/1/25",14,"0.70");
        expect(rows,"1/1/25",15,"-17.03");
        expect(rows,"1/1/25",16,"-17.01");
        expect(rows,"1/1/26",12,"1547.500 nm");
        expect(rows,"1/1/26",15,"");
        expect(rows,"1/1/26",16,"");
        expect(rows,"1/1/27",12,"1561.419 nm");
        expect(rows,"1/1/27",15,"+1.25");
        expect(rows,"1/1/28",15,"-5.00");
        expect(rows,"1/1/28",16,"");
        expect(rows,"1/1/29",15,"");

        String one = lanes(1,"1 +39.6 - 5.00 -20.36\n");
        String four = lanes(4,"1 - 45.5 2.81 -3.49/H-W\n2 - 45.7 3.08 -4.43\n"
                + "3 - 41.9 2.75 -4.47\n4 - 40.9 3.28 -4.21/L-WA\n");
        String partial = lanes(4,"1 - 40.0 1.00 -10.00\n2 - 40.0 1.00 -10.00\n");
        String incompleteRx = lanes(1,"1 - - 0.00 N/A\n");
        String laneLog = port("5/1/1","1546 nm",one)+port("1/1/c33","1310 nm",four)
                +port("1/1/30","1310 nm",partial)+port("1/1/31","1310 nm",partial)
                +port("1/1/32","1310 nm",incompleteRx);
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            rows=parse(laneLog);
        } finally { Locale.setDefault(saved); }
        expect(rows,"5/1/1",14,"5.00");
        expect(rows,"5/1/1",15,"-20.36");
        expect(rows,"1/1/c33",14,"2.98");
        expect(rows,"1/1/c33",15,"-4.15");
        expect(rows,"1/1/30",15,"");
        expect(rows,"1/1/31",15,"");
        expect(rows,"1/1/32",14,"0.00");
        expect(rows,"1/1/32",15,"");

        String child = "A:TEST#show port 1/1/c33/1 ethernet lldp remote-info\n"
                + "Remote Peer Index 1 at timestamp 1:\nChassis Id : 00:11:22:33:44:55\n"
                + "Port Id : 1/1/c2/1\nSystem Name : PEER\nSystem Description : Nokia\n"
                + "A:TEST#show port 1/1/c33/1\nDescription : To_PEER\nInterface : 1/1/c33/1\nOper State : up\n";
        rows=parse(port("1/1/c33","1310 nm",four)+child);
        expect(rows,"1/1/c33/1",15,"-4.15");
        verifyLive(log+laneLog);
        System.out.println("PASS LinkOpticalNokiaMetricsRegression: decimal wavelengths, scalar flags, missing thresholds, lane counts, flags, isolation, parent port, locale and live values");
    }

    private static void verifyLive(String log) {
        Map<String,LiveNodeHealthParser.PortSnapshot> live=new LinkedHashMap<>();
        for(LiveNodeHealthParser.PortSnapshot r:LiveNodeHealthParser.parsePorts("TEST","10.0.0.1","N-LLDP-Link_OPTIC",wrap(log))) live.put(r.port,r);
        if(!"1307.500nm".equals(live.get("1/1/25").wavelength)
                || live.get("1/1/25").rxPowerDbm != -17.03
                || !"[-17.01<>2.00]".equals(live.get("1/1/25").rxWarningRange)
                || live.get("5/1/1").rxPowerDbm != -20.36
                || live.get("1/1/c33").rxPowerDbm != -4.15
                || live.get("1/1/26").rxPowerDbm != null
                || !live.get("1/1/26").rxWarningRange.isEmpty()) throw new AssertionError("Live values differ from CSV");
    }

    private static String lanes(int count,String values) {
        return "Number of Lanes : "+count+"\nLane Rx Optical Pwr (avg dBm) 7.50 4.50 -10.60 -13.61\n"
                +"Lane ID Temp(C)/Alm Tx Bias(mA)/Alm Tx Pwr(dBm)/Alm Rx Pwr(dBm)/Alm\n---\n"+values;
    }
    private static String port(String id,String wavelength,String ddm) {
        return "A:TEST#show port "+id+" ethernet lldp remote-info\nNo remote peers found\n"
                +"A:TEST#show port "+id+"\nDescription : Test_port\nInterface : "+id+"\n"
                +"Admin State : up\nOper State : up\nTX Laser Wavelength: "+wavelength+" Diag Capable : yes\n"
                +"Link Length support: 40km for SMF\n"+ddm;
    }
    private static String wrap(String log) { return "A:TEST#show version\nTiMOS-B-10.0.R8\n"+log+"A:TEST#logout\n"; }
    private static Map<String,String[]> parse(String log) throws Exception {
        String csv=Check_Link_Optical.Sub(new BufferedReader(new StringReader(wrap(log))),"[1]10.0.0.1_TEST_N-LLDP-Link_OPTIC_2026-09-21.txt");
        Map<String,String[]> rows=new LinkedHashMap<>();
        for(String line:csv.trim().split("\\r?\\n")) {
            String[] f=line.split(",",-1);
            if(f.length!=20) throw new AssertionError("Malformed CSV: "+line);
            rows.put(f[2].replace("'",""),f);
        }
        return rows;
    }
    private static void expect(Map<String,String[]> rows,String port,int column,String expected) {
        String[] row=rows.get(port);
        if(row==null || !expected.equals(row[column])) throw new AssertionError(port+" column "+column+" expected ["+expected+"] got "+(row==null?"missing":"["+row[column]+"]"));
    }
}
