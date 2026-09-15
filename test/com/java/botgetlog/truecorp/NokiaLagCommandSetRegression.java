package com.java.botgetlog.truecorp;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;

/** Offline checks for the distributed command matrix and existing-user update. */
public final class NokiaLagCommandSetRegression {
    private static final String SET="N-LLDP-Link_OPTIC", COMMAND="show lag description";
    public static void main(String[] args) throws Exception {
        Path current=Paths.get(args[0]),previous=Paths.get(args[1]),root=Paths.get(args[2]);
        check(!Files.exists(root),"Use a fresh test directory");Files.createDirectories(root);
        Map<String,String> before=cells(previous,false),after=cells(current,false);
        Set<String> changed=new TreeSet<String>();
        for(String key:union(before.keySet(),after.keySet()))if(!Objects.equals(before.get(key),after.get(key)))changed.add(key);
        check(changed.equals(new TreeSet<String>(Arrays.asList("cmdSet:8:6","cmdSet:9:6"))),"Unexpected workbook changes: "+changed);
        List<String> commands=commands(current,SET);
        check(commands.equals(Arrays.asList("environment no more","show system cpu","show system memory-pools","show system lldp neighbor","show chassis","show version","show port",COMMAND,"logout")),"Wrong Nokia command order");
        check(!commands(current,"HW-LLDP-Link_OPTIC").contains(COMMAND),"Nokia command leaked into Huawei");
        check(!commands(current,"ZTE-LLDP-Link_OPTIC").contains(COMMAND),"Nokia command leaked into ZTE");
        Path installed=root.resolve("installed"),stage=root.resolve("stage");Files.createDirectories(installed);Files.createDirectories(stage.resolve("defaults"));
        Path user=installed.resolve("UserInterface_Input.xlsx");Files.copy(previous,user);Files.copy(current,stage.resolve("defaults/UserInterface_Input.xlsx"));
        try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(Files.readAllBytes(user)))){
            book.createSheet("UserDataPreservationTest").createRow(0).createCell(0).setCellValue("Keep custom user data");
            try(OutputStream out=Files.newOutputStream(user)){book.write(out);}
        }
        Map<String,String> userBefore=cells(user,true);
        Method sync=Class.forName("com.java.updater.UpdaterMain").getDeclaredMethod("synchronizeUserInputCmdSet",Path.class,Path.class);sync.setAccessible(true);sync.invoke(null,installed,stage);
        check(userBefore.equals(cells(user,true)),"User data changed outside cmdSet");
        check(commands(user,SET).equals(commands),"Existing user did not receive Nokia command");
        Map<String,String> once=cells(user,false);sync.invoke(null,installed,stage);check(once.equals(cells(user,false)),"Repeated update duplicated commands");
        check(Files.isDirectory(installed.resolve("_output/System_Log/Input_Backup")),"Missing user workbook backup");
        System.out.println("Nokia_LACP_COMMAND_REGRESSION_OK: command order, vendor isolation, unrelated cells, existing-user sync, user data preservation, idempotence, backup");
    }
    static List<String> commands(Path file,String name)throws Exception{
        try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(Files.readAllBytes(file)))){
            Sheet sheet=book.getSheet("cmdSet");int col=-1;DataFormatter fmt=new DataFormatter(Locale.ROOT);
            for(Cell c:sheet.getRow(0))if(name.equals(fmt.formatCellValue(c).trim()))col=c.getColumnIndex();check(col>=0,"Missing "+name);
            List<String> result=new ArrayList<String>();
            for(int r=1;r<=sheet.getLastRowNum();r++){Row row=sheet.getRow(r);String value=row==null?"":fmt.formatCellValue(row.getCell(col)).trim();if(!value.isEmpty())result.add(value);if("quit".equals(value)||"logout".equals(value))break;}return result;
        }
    }
    static Map<String,String> cells(Path file,boolean ignoreCommands)throws Exception{
        Map<String,String> result=new TreeMap<String,String>();
        try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(Files.readAllBytes(file)))){
            for(Sheet sheet:book){if(ignoreCommands&&"cmdSet".equals(sheet.getSheetName()))continue;for(Row row:sheet)for(Cell cell:row)if(cell.getCellType()!=CellType.BLANK)result.put(sheet.getSheetName()+":"+row.getRowNum()+":"+cell.getColumnIndex(),cell.getCellType()+":"+cell.toString());}
        }return result;
    }
    static <T> Set<T> union(Set<T> a,Set<T> b){Set<T> s=new HashSet<T>(a);s.addAll(b);return s;}
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
