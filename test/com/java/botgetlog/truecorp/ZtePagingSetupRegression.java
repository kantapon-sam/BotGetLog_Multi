package com.java.botgetlog.truecorp;

import com.java.shared.ZtePartialLog;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Offline classification checks; never connects or changes retained captures. */
public final class ZtePagingSetupRegression {
    private static final String SET = "ZTE-LLDP-Link_OPTIC";
    private static final String NAME = "[1]10.1.2.3_CPE-TEST0001_" + SET + "_2026-11-05.txt";
    private static final String REJECTION = "terminal length 0\n         ^\n"
            + "%Error 140303: Invalid input detected at '^' marker.\n";
    private static final String OPTICAL = "Interface Type Wavelength RxPower(dBm) TxPower(dBm) Status\n"
            + "gei-0/1/0/1 1G-10km-SFP 1310nm -4.0 -5.0 Normal\n";
    private static final String INTERFACES = "gei-0/1/0/1 is up, ifindex: 8271\n"
            + "  Line protocol is up, IPv4 protocol is up, IPv6 protocol is down\n  BW 1 Gbit/s\n";
    private static int checks;

    private static void require(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
    private static void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
    private static String full() {
        String text = ZtePartialLogRegression.sample();
        int first = text.indexOf(ZtePartialLogRegression.ERROR);
        text = text.substring(0, first) + OPTICAL + text.substring(first + ZtePartialLogRegression.ERROR.length());
        text = text.replace(ZtePartialLogRegression.ERROR, INTERFACES);
        return text.replace("#terminal length 0\n", "#terminal length 0\n" + REJECTION);
    }
    private static void verifyComplete(File file) throws Exception {
        byte[] original = Files.readAllBytes(file.toPath());
        require(ZtePartialLog.inspect(file).isCompleteWithPagingFallback(), "verified paging fallback");
        require(!ZtePartialLog.inspect(file).isPartial(), "setup warning is not missing data");
        require(!Telnet_Multi.hasWrongVendorSignal(file), "setup warning is not a rejected data command");
        require(BotGetLog_TrueCorp.isLogCompleteForCmdSet(file, SET), "full completion and daily protection");
        require(BotGetLog_TrueCorp.isLogUsableForCmdSet(file, SET), "usable full export");
        require(Arrays.equals(original, Files.readAllBytes(file.toPath())), "raw capture preserved");
    }
    public static void main(String[] args) throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("cmdSet");
            String[] commands = {SET, "terminal length 0", "show processor", "show lldp neighbor brief",
                    "show version", "show opt brief", "show interface", "show lacp internal", "quit"};
            for (int i = 0; i < commands.length; i++) sheet.createRow(i).createCell(0).setCellValue(commands[i]);
            java.lang.reflect.Method rebuild = BotGetLog_TrueCorp.class.getDeclaredMethod("rebuildExcelCache", Workbook.class);
            rebuild.setAccessible(true);
            rebuild.invoke(null, book);
        }
        Path dir = Files.createTempDirectory("zte-paging-setup-");
        File file = dir.resolve(NAME).toFile();
        String valid = full();
        try {
            write(file, valid);
            verifyComplete(file);
            write(file, valid.replace(REJECTION, ""));
            require(!Telnet_Multi.hasWrongVendorSignal(file)
                    && BotGetLog_TrueCorp.isLogCompleteForCmdSet(file, SET), "successful setup unchanged");
            for (String bad : Arrays.asList(
                    valid.replace("140303", "140304"),
                    valid.replace(REJECTION, "") .replace("#show processor\n", "#show processor\n" + REJECTION),
                    valid.replace("#show version", "#show WRONG"),
                    valid.replace("CPE-TEST0001#show interface", "CPE-OTHER#show interface"),
                    valid.replace("Connection closed by foreign host.\n", ""),
                    valid.replace("#quit", "#exit"),
                    valid.replace("Total neighbors: 1", "Total neighbors: 2"),
                    valid.replace("ZTE ZXCTN Software, Version:", "unknown version:"),
                    valid.replace(OPTICAL, ""), valid.replace(INTERFACES, ""),
                    valid.replace("Port[Flags] State", ""),
                    valid.replace("         ^\n", ""),
                    valid.replace("#show lacp internal", "--More--\nCPE-TEST0001#show lacp internal"),
                    valid.replace("#quit", "#quit\n[BOT-COLLECTION-INCOMPLETE] interrupted"),
                    valid.replace(INTERFACES, INTERFACES + "%Error 140303: Invalid input detected at '^' marker.\n"),
                    valid + "%Error 140303: Invalid input detected at '^' marker.\n")) {
                write(file, bad);
                require(Telnet_Multi.hasWrongVendorSignal(file), "unverified or different error rejected");
                require(!BotGetLog_TrueCorp.isLogUsableForCmdSet(file, SET), "unsafe capture excluded");
            }
            for (String section : Arrays.asList(OPTICAL, INTERFACES)) {
                write(file, valid.replace(section, ZtePartialLogRegression.ERROR));
                require(ZtePartialLog.inspect(file).isPartial(), "setup warning plus service error is partial");
                require(!ZtePartialLog.inspect(file).isCompleteWithPagingFallback(), "partial not full");
                require(!BotGetLog_TrueCorp.isLogCompleteForCmdSet(file, SET), "partial remains retryable");
                require(BotGetLog_TrueCorp.isLogUsableForCmdSet(file, SET), "partial export retained");
            }
            write(file, valid);
            require(!ZtePartialLog.inspect(Arrays.asList(valid.split("\n")), NAME.replace("ZTE-", "HW-")).isCompleteWithPagingFallback(),
                    "other vendors excluded");
            for (String path : args) verifyComplete(new File(path));
        } finally {
            Files.deleteIfExists(file.toPath());
            Files.delete(dir);
        }
        System.out.println("PASS ZtePagingSetupRegression: " + checks + " checks");
    }
}
