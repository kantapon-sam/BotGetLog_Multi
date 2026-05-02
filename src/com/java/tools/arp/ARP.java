package com.java.tools.arp;

import com.java.botgetlog.AppConsole;
import com.java.launcher.LauncherGate;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;

public class ARP {

    private static final int MAX_LINES_PER_FILE = 500000;
    private static final String CSV_HEADER = "Node,Model,Loopback,IP ADDRESS,MAC ADDRESS,INTERFACE,VPN-INSTANCE,PHY,Protocol,Description";

    private static String buildOutputFileName(String formattedDateTime, int fileIndex) {
        return "DataARP_" + formattedDateTime + "_" + fileIndex + ".csv";
    }

    private static void writeCsvPart(PathFile f, String formattedDateTime, int fileIndex, StringBuilder body) throws Exception {
        String outputFile = buildOutputFileName(formattedDateTime, fileIndex);
        FileWriter writer = new FileWriter(f.getOptical() + "\\" + outputFile, false);
        Writer.A(writer, CSV_HEADER + body.toString());
        System.out.println(outputFile);
    }

    public static void main(String[] args) {
        if (LauncherGate.redirectToLauncherIfNeeded("ARP")) {
            return;
        }
        AppConsole.install("ARP Console", "ARP - Console");
        Dialog.setLAF();
        System.out.println("[INFO] ARP started");

        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");
        String formattedDateTime = now.format(formatter);

        try {
            int TotalFile = 0;

            PathFile f = new PathFile();
            File[] files = f.getFile().listFiles();
            if (files == null) {
                throw new Exception("Input folder not found or empty");
            }

            Arrays.sort(files, new Comparator<File>() {
                public int compare(File f1, File f2) {
                    return Long.compare(f1.lastModified(), f2.lastModified());
                }
            });

            for (int i = 0; i < files.length; i++) {
                if (files[i].getName().contains("ARP")) {
                    TotalFile++;
                }
            }
            System.out.println("[INFO] Found " + TotalFile + " ARP file(s) to process");
            if (TotalFile == 0) {
                String message = "No ARP input files found in _output\\Total_Log";
                System.out.println("[WARN] " + message);
                Dialog.Info(message);
                return;
            }

            StringBuilder csvBody = new StringBuilder();
            int currentLineCount = 0;
            int outputFileCount = 0;

            for (int i = 0; i < files.length; i++) {
                if (files[i].getName().contains("ARP_")) {
                    System.out.println("[PROCESS] Reading " + files[i].getName());
                    BufferedReader br_PTP = new BufferedReader(new FileReader(files[i]));
                    String pathOutput = files[i].getName().replaceFirst("\\.txt$", "");
                    String parsedRows = CheckARP.Sub(br_PTP, pathOutput);
                    br_PTP.close();

                    if (parsedRows == null || parsedRows.trim().isEmpty()) {
                        continue;
                    }

                    String[] rows = parsedRows.split("\\r?\\n");
                    for (int j = 0; j < rows.length; j++) {
                        String row = rows[j].trim();
                        if (row.isEmpty()) {
                            continue;
                        }

                        if (currentLineCount >= MAX_LINES_PER_FILE) {
                            outputFileCount++;
                            writeCsvPart(f, formattedDateTime, outputFileCount, csvBody);
                            csvBody.setLength(0);
                            currentLineCount = 0;
                        }

                        csvBody.append("\n").append(row);
                        currentLineCount++;
                    }
                }
            }

            if (currentLineCount > 0 || outputFileCount == 0) {
                outputFileCount++;
                writeCsvPart(f, formattedDateTime, outputFileCount, csvBody);
            }
            System.out.println("[INFO] Generated " + outputFileCount + " ARP output file(s)");

            String outputSummary;
            if (outputFileCount == 1) {
                outputSummary = buildOutputFileName(formattedDateTime, 1);
            } else {
                outputSummary = buildOutputFileName(formattedDateTime, 1)
                        + " à¸–à¸¶à¸‡ "
                        + buildOutputFileName(formattedDateTime, outputFileCount)
                        + " (" + outputFileCount + " files)";
            }

            outputSummary = "Generated " + outputFileCount + " ARP file(s)\n" + outputSummary;

            System.out.println(TotalFile + " Node");
            Dialog.Success(outputSummary, TotalFile);
        } catch (Exception ex) {
            System.out.println(ex.toString());
            ex.toString();
        }
    }
}

