package com.java.tools.ptp;

import com.java.shared.AppConsole;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class PTP {

    private static String writeCsvIfHasData(PathFile f, String outputFile, String header, String body) throws Exception {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }

        FileWriter writer = new FileWriter(f.getOptical() + "\\" + outputFile, true);
        Writer.A(writer, header + body);
        System.out.println("[INFO] Generated " + outputFile);
        return outputFile;
    }

    public static void main(String[] args) {
        AppConsole.install("PTP Console", "PTP - Console");
        Dialog.setLAF();
        System.out.println("[INFO] PTP started");

        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");
        String formattedDateTime = now.format(formatter);

        try {
            int totalFile = 0;
            String headerStatusZte = "Site,Parent id,Grandmaster id,Time status,Domain,Steps removed";
            String bodyStatusZte = "";
            String headerPortZte = "Site,PortName,VLAN,PortNum,Enable,PortState,D-m,Type";
            String bodyPortZte = "";

            String headerStatusNokia = "Site,Parent Clock Id,GM Clock Id,Timescale,Domain,PTP Recovery State";
            String bodyStatusNokia = "";
            String headerPortNokia = "Site,Port,PTP Adm/Opr,PTP State,Neighbors,Tx Rate,Rx Rate";
            String bodyPortNokia = "";

            String headerStatusHuawei = "Site,Parent Clock Id,GM Clock Id,Timescale,Domain,Steps removed,Realtime(T2-T1),Max(T2-T1),Min(T2-T1)";
            String bodyStatusHuawei = "";
            String headerPortHuawei = "Site,Name,State,Delay-mech,Ann-timeout,Type,Domain";
            String bodyPortHuawei = "";

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
                if (files[i].getName().contains("PTP")) {
                    totalFile++;
                }
            }

            System.out.println("[INFO] Found " + totalFile + " PTP file(s) to process");
            if (totalFile == 0) {
                String message = "No PTP input files found in _output\\Total_Log";
                System.out.println("[WARN] " + message);
                Dialog.Info(message);
                return;
            }

            for (int i = 0; i < files.length; i++) {
                if (files[i].getName().contains("ZTE-PTP")) {
                    System.out.println("[PROCESS] Reading " + files[i].getName());
                    BufferedReader brPtp = new BufferedReader(new FileReader(files[i]));
                    BufferedReader brPtpPort = new BufferedReader(new FileReader(files[i]));
                    String pathOutput = files[i].getName().split(".txt")[0];
                    bodyStatusZte += CheckPTP_ZTE.Sub(brPtp, pathOutput);
                    bodyPortZte += CheckPTP_Port_ZTE.Sub(brPtpPort, pathOutput);
                    brPtp.close();
                    brPtpPort.close();
                } else if (files[i].getName().contains("N-PTP")) {
                    System.out.println("[PROCESS] Reading " + files[i].getName());
                    BufferedReader brPtp = new BufferedReader(new FileReader(files[i]));
                    BufferedReader brPtpPort = new BufferedReader(new FileReader(files[i]));
                    String pathOutput = files[i].getName().split(".txt")[0];
                    bodyStatusNokia += CheckPTP_Nokia.Sub(brPtp, pathOutput);
                    bodyPortNokia += CheckPTP_Port_Nokia.Sub(brPtpPort, pathOutput);
                    brPtp.close();
                    brPtpPort.close();
                } else if (files[i].getName().contains("HW-PTP")) {
                    System.out.println("[PROCESS] Reading " + files[i].getName());
                    BufferedReader brPtp = new BufferedReader(new FileReader(files[i]));
                    BufferedReader brPtpPort = new BufferedReader(new FileReader(files[i]));
                    String pathOutput = files[i].getName().split(".txt")[0];
                    bodyStatusHuawei += CheckPTP_Huawei.Sub(brPtp, pathOutput);
                    bodyPortHuawei += CheckPTP_Port_Huawei.Sub(brPtpPort, pathOutput);
                    brPtp.close();
                    brPtpPort.close();
                }
            }

            List<String> generatedFiles = new ArrayList<String>();
            String generated = writeCsvIfHasData(f, "DataPTP-status_ZTE_" + formattedDateTime + ".csv", headerStatusZte, bodyStatusZte);
            if (generated != null) {
                generatedFiles.add(generated);
            }

            generated = writeCsvIfHasData(f, "DataPTP-Port_ZTE_" + formattedDateTime + ".csv", headerPortZte, bodyPortZte);
            if (generated != null) {
                generatedFiles.add(generated);
            }

            generated = writeCsvIfHasData(f, "DataPTP-status_Nokia_" + formattedDateTime + ".csv", headerStatusNokia, bodyStatusNokia);
            if (generated != null) {
                generatedFiles.add(generated);
            }

            generated = writeCsvIfHasData(f, "DataPTP-port_Nokia_" + formattedDateTime + ".csv", headerPortNokia, bodyPortNokia);
            if (generated != null) {
                generatedFiles.add(generated);
            }

            generated = writeCsvIfHasData(f, "DataPTP-status_Huawei_" + formattedDateTime + ".csv", headerStatusHuawei, bodyStatusHuawei);
            if (generated != null) {
                generatedFiles.add(generated);
            }

            generated = writeCsvIfHasData(f, "DataPTP-port_Huawei_" + formattedDateTime + ".csv", headerPortHuawei, bodyPortHuawei);
            if (generated != null) {
                generatedFiles.add(generated);
            }

            if (generatedFiles.isEmpty()) {
                String message = "No PTP rows were parsed from the input file(s)";
                System.out.println("[WARN] " + message);
                Dialog.Info(message);
                return;
            }

            StringBuilder successSummary = new StringBuilder();
            successSummary.append("Generated ").append(generatedFiles.size()).append(" PTP file(s)");
            for (int i = 0; i < generatedFiles.size(); i++) {
                String name = generatedFiles.get(i);
                System.out.println(name);
                successSummary.append("\n").append(name);
            }

            System.out.println(totalFile + " Node");
            Dialog.Success(successSummary.toString(), totalFile);
        } catch (Exception ex) {
            System.out.println(ex.toString());
            ex.toString();
        }
    }
}

