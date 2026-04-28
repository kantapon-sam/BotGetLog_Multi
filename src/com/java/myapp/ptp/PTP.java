package com.java.myapp.ptp;

import com.java.myapp.LauncherGate;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;

public class PTP {

    public static void main(String[] args) {
        if (LauncherGate.redirectToLauncherIfNeeded("PTP")) {
            return;
        }
        Dialog.setLAF();
        Dialog D = new Dialog();
        // Get the current date and time
        LocalDateTime now = LocalDateTime.now();

        // Format the date and time
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");
        String formattedDateTime = now.format(formatter);
        try {

            int TotalFile = 0;
            String Str_allPTP_status_ZTE = "Site,Parent id,Grandmaster id,Time status,Domain,Steps removed";
            String Str_PTP_ZTE = "";
            String Str_allPTP_Port_ZTE = "Site,PortName,VLAN,PortNum,Enable,PortState,D-m,Type";
            String Str_PTP_Port_ZTE = "";

            String Str_allPTP_status_Nokia = "Site,Parent Clock Id,GM Clock Id,Timescale,Domain,PTP Recovery State";
            String Str_PTP_Nokia = "";
            String Str_allPTP_Port_Nokia = "Site,Port,PTP Adm/Opr,PTP State,Neighbors,Tx Rate,Rx Rate";
            String Str_PTP_Port_Nokia = "";
            
             String Str_allPTP_status_Huawei = "Site,Parent Clock Id,GM Clock Id,Timescale,Domain,Steps removed,Realtime(T2-T1),Max(T2-T1),Min(T2-T1)";
            String Str_PTP_Huawei = "";
            String Str_allPTP_Port_Huawei = "Site,Name,State,Delay-mech,Ann-timeout,Type,Domain";
            String Str_PTP_Port_Huawei = "";
            
            PathFile f = new PathFile();
            File[] files = f.getFile().listFiles();

            Arrays.sort(files, new Comparator<File>() {
                public int compare(File f1, File f2) {
                    return Long.compare(f1.lastModified(), f2.lastModified());
                }
            });

            for (int i = 0; i < files.length; i++) {
                if (files[i].getName().contains("PTP")) {
                    TotalFile++;
                }
            }

            for (int i = 0; i < files.length; i++) {
                if (files[i].getName().contains("ZTE-PTP")) {
                    BufferedReader br_PTP, br_PTP_Port;
                    br_PTP = new BufferedReader(new FileReader(files[i]));
                    br_PTP_Port = new BufferedReader(new FileReader(files[i]));
                    String pathOutput = files[i].getName().split(".txt")[0];
                    Str_PTP_ZTE += CheckPTP_ZTE.Sub(br_PTP, pathOutput);
                    Str_PTP_Port_ZTE += CheckPTP_Port_ZTE.Sub(br_PTP_Port, pathOutput);
                    br_PTP.close();
                    br_PTP_Port.close();
                } else if (files[i].getName().contains("N-PTP")) {
                    BufferedReader br_PTP, br_PTP_Port;
                    br_PTP = new BufferedReader(new FileReader(files[i]));
                    br_PTP_Port = new BufferedReader(new FileReader(files[i]));
                    String pathOutput = files[i].getName().split(".txt")[0];
                    Str_PTP_Nokia += CheckPTP_Nokia.Sub(br_PTP, pathOutput);
                    Str_PTP_Port_Nokia += CheckPTP_Port_Nokia.Sub(br_PTP_Port, pathOutput);
                    br_PTP.close();
                    br_PTP_Port.close();
                }else if (files[i].getName().contains("HW-PTP")) {
                    BufferedReader br_PTP, br_PTP_Port;
                    br_PTP = new BufferedReader(new FileReader(files[i]));
                    br_PTP_Port = new BufferedReader(new FileReader(files[i]));
                    String pathOutput = files[i].getName().split(".txt")[0];
                    Str_PTP_Huawei += CheckPTP_Huawei.Sub(br_PTP, pathOutput);
                    Str_PTP_Port_Huawei += CheckPTP_Port_Huawei.Sub(br_PTP_Port, pathOutput);
                    br_PTP.close();
                    br_PTP_Port.close();
                }
            }

            Str_allPTP_status_ZTE += Str_PTP_ZTE;
            String output_ZTE = "DataPTP-status_ZTE_" + formattedDateTime + ".csv";
            FileWriter A;
            A = new FileWriter(f.getOptical() + "\\" + output_ZTE, true);
            Writer.A(A, Str_allPTP_status_ZTE);

            Str_allPTP_Port_ZTE += Str_PTP_Port_ZTE;
            String output_Port_ZTE = "DataPTP-Port_ZTE_" + formattedDateTime + ".csv";
            FileWriter AA;
            AA = new FileWriter(f.getOptical() + "\\" + output_Port_ZTE, true);
            Writer.A(AA, Str_allPTP_Port_ZTE);

            Str_allPTP_status_Nokia += Str_PTP_Nokia;
            String output_Nokia = "DataPTP-status_Nokia_" + formattedDateTime + ".csv";
            FileWriter B;
            B = new FileWriter(f.getOptical() + "\\" + output_Nokia, true);
            Writer.A(B, Str_allPTP_status_Nokia);

            Str_allPTP_Port_Nokia += Str_PTP_Port_Nokia;
            String output_Port_Nokia = "DataPTP-port_Nokia_" + formattedDateTime + ".csv";
            FileWriter BB;
            BB = new FileWriter(f.getOptical() + "\\" + output_Port_Nokia, true);
            Writer.A(BB, Str_allPTP_Port_Nokia);
            
             Str_allPTP_status_Huawei += Str_PTP_Huawei;
            String output_Huawei = "DataPTP-status_Huawei_" + formattedDateTime + ".csv";
            FileWriter C;
            C = new FileWriter(f.getOptical() + "\\" + output_Huawei, true);
            Writer.A(C, Str_allPTP_status_Huawei);

            Str_allPTP_Port_Huawei += Str_PTP_Port_Huawei;
            String output_Port_Huawei = "DataPTP-port_Huawei_" + formattedDateTime + ".csv";
            FileWriter CC;
            CC = new FileWriter(f.getOptical() + "\\" + output_Port_Huawei, true);
            Writer.A(CC, Str_allPTP_Port_Huawei);

            System.out.println(output_ZTE);
            System.out.println(output_Port_ZTE);
            System.out.println(output_Nokia);
            System.out.println(output_Port_Nokia);
               System.out.println(output_Huawei);
            System.out.println(output_Port_Huawei);
            System.out.println(TotalFile + " Node");
            Dialog.Success(output_Nokia, TotalFile);
        } catch (Exception ex) {
            System.out.println(ex.toString());
            ex.toString();
        }
    }

}
