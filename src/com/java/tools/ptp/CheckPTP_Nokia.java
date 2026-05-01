package com.java.tools.ptp;

import java.io.BufferedReader;
import java.io.IOException;

public class CheckPTP_Nokia {

    public static String Sub(BufferedReader br, String path) throws IOException {
        String line;
        String Str_all_PTP = "";
        String Str_PTP = "";

        String hostname[] = new String[10000];
        int h = 0;
        String Parent_id = "";
        String Grandmaster_id = "";
        String Time_status = "";
        String Domain_value = "";
        String Steps_removed = "";
    if (path.contains("_N-PTP_")) {

            while ((line = br.readLine()) != null) {
                if (line.contains("environment ")) {
                    hostname[h] = line.split("#")[0].split(":")[1];
                    h++;
                }
                if (line.contains("Parent Clock Id")) {
                    Parent_id = line.split(": ")[1].split(" ")[0];
                }
                if (line.contains("GM Clock Id")) {
                    Grandmaster_id = line.split(": ")[1].split(" ")[0];
                }
                if (line.contains("Timescale")) {
                    Time_status = line.split(": ")[1];
                }
                if (line.contains("Domain")) {
                    Domain_value = line.split(": ")[1].split(" ")[0];
                }
                if (line.contains("PTP Recovery State")) {
                    Steps_removed = line.split(": ")[1].split(" ")[0];
               
                }
                Str_PTP = "\n" + hostname[0] + "," + Parent_id + "," + Grandmaster_id + "," + Time_status + "," + Domain_value + "," + Steps_removed;
            }

        }

        br.close();

        return Str_PTP;
    }

}

