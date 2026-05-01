package com.java.tools.ptp;

import java.io.BufferedReader;
import java.io.IOException;

public class CheckPTP_Port_Huawei {

    public static String Sub(BufferedReader br, String path) throws IOException {
        String line;
        boolean insideTable = false;
        String Str_all_PTP = "";
        String Str_PTP = "";

        String hostname[] = new String[10000];
        int h = 0;

        if (path.contains("_HW-PTP_")) {

            while ((line = br.readLine()) != null) {
                String trimmedLine = line.trim();
                if (trimmedLine.startsWith("<") && trimmedLine.contains(">screen-length")) {
                    String[] promptParts = trimmedLine.split("[<>]", 3);
                    if (promptParts.length > 1) {
                        hostname[h] = promptParts[1];
                        h++;
                    }
                }

                if (line.trim().startsWith("Name")) {
                    insideTable = true;
                    continue;
                }
                // à¸«à¸¢à¸¸à¸”à¹€à¸¡à¸·à¹ˆà¸­à¹€à¸ˆà¸­ Time Performance à¸«à¸£à¸·à¸­à¹€à¸ªà¹‰à¸™à¸‚à¸µà¸”à¹ƒà¸«à¸¡à¹ˆ
                if (line.contains("Time Performance")) {
                    break;
                }
                if (insideTable && !line.trim().isEmpty() && !line.contains("----")) {
                    // à¸•à¸±à¸”à¸šà¸£à¸£à¸—à¸±à¸”à¸”à¹‰à¸§à¸¢à¸Šà¹ˆà¸­à¸‡à¸§à¹ˆà¸²à¸‡ (regex \\s+)
                    String[] parts = line.trim().split("\\s+");
                    // join à¹€à¸›à¹‡à¸™ CSV
                   // System.out.println(String.join(",", parts));
                    
                     Str_PTP += "\n" + hostname[0] + "," + String.join(",", parts);
                }
            }

        }

        br.close();

        return Str_PTP;
    }

}

