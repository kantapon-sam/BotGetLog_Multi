package com.java.tools.ptp;

import java.io.BufferedReader;
import java.io.IOException;

public class CheckPTP_ZTE {

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
        if (path.contains("_ZTE-PTP_")) {

            while ((line = br.readLine()) != null) {
                if (line.contains("#terminal length 0")) {
                    hostname[h] = line.split("#")[0];
                    h++;
                }
                if (line.contains("Parent id")) {
                    Parent_id = line.split(": ")[1];
                }
                if (line.contains("Grandmaster id")) {
                    Grandmaster_id = line.split(": ")[1];
                }
                if (line.contains("Time status")) {
                    Time_status = line.split(": ")[1];
                }
                if (line.contains("Domain value")) {
                    Domain_value = line.split(": ")[1];
                }
                if (line.contains("Steps removed")) {
                    Steps_removed = line.split(": ")[1];
                }
                Str_PTP = "\n" + hostname[0] + "," + Parent_id + "," + Grandmaster_id + "," + Time_status + "," + Domain_value + "," + Steps_removed;
            }

        } 

        if (path.contains("PTP")) {
            System.out.println("Done " + path);
        }
        br.close();

        return Str_PTP;
    }

}

