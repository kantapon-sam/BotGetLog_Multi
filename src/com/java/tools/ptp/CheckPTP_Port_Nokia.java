package com.java.tools.ptp;

import java.io.BufferedReader;
import java.io.IOException;

public class CheckPTP_Port_Nokia {

    public static String Sub(BufferedReader br, String path) throws IOException {
        String line;
        boolean insideTable = false;
        String Str_all_PTP = "";
        String Str_PTP = "";

        String hostname[] = new String[10000];
        int h = 0;

        if (path.contains("_N-PTP_")) {

            while ((line = br.readLine()) != null) {
                if (line.contains("environment ")) {
                    hostname[h] = line.split("#")[0].split(":")[1];
                    h++;
                }
                if (line.contains("PTP Adm/Opr")) {
                    insideTable = true; // à¹€à¸£à¸´à¹ˆà¸¡à¸­à¹ˆà¸²à¸™à¸‚à¹‰à¸­à¸¡à¸¹à¸¥
                    continue;
                }
                if (insideTable) {
                    // à¸‚à¹‰à¸²à¸¡à¹€à¸ªà¹‰à¸™à¹à¸šà¹ˆà¸‡à¸«à¸£à¸·à¸­à¸šà¸£à¸£à¸—à¸±à¸”à¸§à¹ˆà¸²à¸‡
                    if (line.isEmpty() || line.startsWith("--") || line.startsWith("No. of PTP")) {
                        continue;
                    }

                    if (line.startsWith("=")) {
                        break; // à¸ˆà¸šà¸šà¸¥à¹‡à¸­à¸
                    }

                    // à¹à¸¢à¸à¹à¸¥à¸°à¸žà¸´à¸¡à¸žà¹Œà¸”à¹‰à¸§à¸¢ comma
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 6) {
                        parts[0] = "'" + parts[0];
                        Str_PTP += "\n" + hostname[0] + "," + String.join(",", parts);
                    }
                }
            }

        }

        br.close();

        return Str_PTP;
    }

}

