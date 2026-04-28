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
                // à¸–à¹‰à¸²à¸à¸³à¸¥à¸±à¸‡à¸­à¸¢à¸¹à¹ˆà¹ƒà¸™à¸Šà¹ˆà¸§à¸‡à¸‚à¸­à¸‡à¸•à¸²à¸£à¸²à¸‡
                if (insideTable) {
                    // à¸«à¸¢à¸¸à¸”à¸­à¹ˆà¸²à¸™à¹€à¸¡à¸·à¹ˆà¸­à¹€à¸ˆà¸­ prompt à¸«à¸£à¸·à¸­à¸šà¸£à¸£à¸—à¸±à¸”à¸§à¹ˆà¸²à¸‡
                    if (line.isEmpty() || line.contains("#") || line.startsWith("CPE-")) {
                        break;
                    }

                    // à¹à¸¢à¸à¸‚à¹‰à¸­à¸¡à¸¹à¸¥à¸”à¹‰à¸§à¸¢ whitespace à¸«à¸¥à¸²à¸¢à¸•à¸±à¸§
                    String[] parts = line.split("\\s+");
                    if (parts.length == 7) {
                       // System.out.println(String.join(",", parts));
                        Str_PTP += "\n" + hostname[0] + "," + String.join(",", parts);
                    }
                }

                // Str_PTP = "\n" + hostname[0] + "," + Parent_id + "," + Grandmaster_id + "," + Time_status + "," + Domain_value + "," + Steps_removed;
            }

        }

        if (path.contains("PTP")) {
            System.out.println("Done " + path);
        }
        br.close();

        return Str_PTP;
    }

}

