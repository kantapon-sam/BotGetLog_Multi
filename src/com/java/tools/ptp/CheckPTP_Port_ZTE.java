package com.java.tools.ptp;

import java.io.BufferedReader;
import java.io.IOException;

public class CheckPTP_Port_ZTE {

    public static String Sub(BufferedReader br, String path) throws IOException {
        String line;
        boolean insideTable = false;
        String Str_all_PTP = "";
        String Str_PTP = "";

        String hostname[] = new String[10000];
        int h = 0;

        if (path.contains("_ZTE-PTP_")) {

            while ((line = br.readLine()) != null) {
                if (line.contains("#terminal length 0")) {
                    hostname[h] = line.split("#")[0];
                    h++;
                }
                if (line.startsWith("PortName")) {
               //     System.out.println(line);
                    insideTable = true;
                    continue;
                }
                if (insideTable) {
                    if (line.isEmpty() || line.contains("#") || line.startsWith("CPE-")) {
                        break;
                    }

                    String[] parts = line.split("\\s+");
                    if (parts.length == 7) {
                       // System.out.println(String.join(",", parts));
                        Str_PTP += "\n" + hostname[0] + "," + String.join(",", parts);
                    }
                }

                // Str_PTP = "\n" + hostname[0] + "," + Parent_id + "," + Grandmaster_id + "," + Time_status + "," + Domain_value + "," + Steps_removed;
            }

        }

        br.close();

        return Str_PTP;
    }

}

