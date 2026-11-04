package com.java.botgetlog.truecorp;
import com.java.shared.ZtePartialLog;
import com.java.tools.linkoptical.Check_Link_Optical;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
public final class ZtePartialLogRegression {
 static final String NAME="[1]10.1.2.3_CPE-TEST0001_ZTE-LLDP-Link_OPTIC_2026-11-04.txt";
 static final String ERROR="%Error 141365: The current operation timeout. Please try again later.(The JID=0x\nb0220001 service does not respond)\n";
 static String sample(){return "CPE-TEST0001#terminal length 0\nCPE-TEST0001#show processor\nCPU(5s) CPU(1m)\nMPU-1/2/0 MSC 10% 9%\n"
 +"CPE-TEST0001#show lldp neighbor brief\nTotal neighbors: 1\nLocal Interface Scope Chassis ID Port ID Holdtime System Name\n"
 +"xgei-1/7/0/1 NB ac00.d0af.4210 xgei-1/4/0/1 90 CPE-PEER0001\n"
 +"CPE-TEST0001#show version\nZXCTN 6180H-A\nZTE ZXCTN Software, Version: ZXCTN 6180HA V5.00.30.20, Release software\n"
 +"CPE-TEST0001#show opt brief\n"+ERROR+"CPE-TEST0001#show interface\n"+ERROR
 +"CPE-TEST0001#show lacp internal\nPort[Flags] State\nCPE-TEST0001#quit\nConnection closed by foreign host.\n";}
 static void require(boolean ok,String detail){if(!ok)throw new AssertionError(detail);}
 static boolean partial(String text,String name){return ZtePartialLog.inspect(Arrays.asList(text.split("\n")),name).isPartial();}
 public static void main(String[] args)throws Exception {
  try(XSSFWorkbook book=new XSSFWorkbook()){
   Sheet sheet=book.createSheet("cmdSet");String[] commands={"ZTE-LLDP-Link_OPTIC","terminal length 0","show processor","show lldp neighbor brief","show version","show opt brief","show interface","show lacp internal","quit"};
   for(int i=0;i<commands.length;i++)sheet.createRow(i).createCell(0).setCellValue(commands[i]);
   java.lang.reflect.Method m=BotGetLog_TrueCorp.class.getDeclaredMethod("rebuildExcelCache",Workbook.class);m.setAccessible(true);m.invoke(null,book);
  }
  String s=sample();require(partial(s,NAME),"finished partial");
  for(String bad:Arrays.asList(s.replace(ERROR,"%Error 1234: invalid command\n"),s.replace("#show version","#show WRONG"),s.replace("CPE-TEST0001#show interface","CPE-OTHER#show interface"),s.replace("Connection closed by foreign host.",""),s.replace("#quit","#exit"),s.replace("#show processor\n","#show processor\n"+ERROR),s.replace("ZTE ZXCTN Software, Version:","unknown version:"),s+"%Error 1234: failure\n"))require(!partial(bad,NAME),"reject malformed/wrong-node/other failure");
  require(!partial(s,NAME.replace("ZTE-","HW-")),"other vendors unaffected");
  require(!partial(s.replace(ERROR,"valid output\n"),NAME),"full not partial");
  Path dir=Files.createTempDirectory("zte-partial-");File file=dir.resolve(NAME).toFile();
  try {
   Files.write(file.toPath(),s.getBytes(StandardCharsets.UTF_8));
   require(!BotGetLog_TrueCorp.isLogCompleteForCmdSet(file,"ZTE-LLDP-Link_OPTIC"),"partial never gains complete/day protection");
   require(BotGetLog_TrueCorp.isLogUsableForCmdSet(file,"ZTE-LLDP-Link_OPTIC"),"usable export");
   require(Telnet_Multi.withAttemptId("[PARTIAL] 10.1.2.3 (CPE-TEST0001, ZTE-LLDP-Link_OPTIC) at 12:00:00","test").endsWith("[ATTEMPT=test]"),"attempt identity");
   String[] row=Check_Link_Optical.Sub(new BufferedReader(new StringReader(s)),file.getPath()).trim().split(",",-1);
   require(row.length==20&&row[3].equals("UNKNOWN")&&row[5].equals("CPE-PEER0001")&&!row[18].isEmpty()&&!row[19].isEmpty(),"usable identity/LLDP");
   for(int i:new int[]{7,8,10,12,13,14,15,16,17})require(row[i].isEmpty(),"unknown not zero: "+i);
   if(args.length>0){int partial=0,full=0;for(File f:new File(args[0]).listFiles()){
    if(ZtePartialLog.inspect(f).isPartial()){partial++;require(!BotGetLog_TrueCorp.isLogCompleteForCmdSet(f,"ZTE-LLDP-Link_OPTIC"),"real partial remains retryable");
     String csv=Check_Link_Optical.Sub(Files.newBufferedReader(f.toPath(),StandardCharsets.UTF_8),f.getPath());require(!csv.trim().isEmpty(),"real export");
     for(String line:csv.trim().split("\n")){String[] r=line.split(",",-1);require(r[3].equals("UNKNOWN")&&r[14].isEmpty()&&r[15].isEmpty(),"real unknown values");}
    }else{full++;require(BotGetLog_TrueCorp.isLogCompleteForCmdSet(f,"ZTE-LLDP-Link_OPTIC"),"real full protected");}}
    require(partial==3&&full==2,"expected 3 partial + 2 full");}
  }finally{Files.deleteIfExists(file.toPath());Files.delete(dir);}
  System.out.println("PASS ZtePartialLogRegression");
 }
}
