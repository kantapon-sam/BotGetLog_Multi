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
                if (line.contains("Time Performance")) {
                    break;
                }
                if (insideTable && !line.trim().isEmpty() && !line.contains("----")) {
                    String[] parts = line.trim().split("\\s+");
                   // System.out.println(String.join(",", parts));
                    
                     Str_PTP += "\n" + hostname[0] + "," + String.join(",", parts);
                }
            }

        }

        br.close();

        return Str_PTP;
    }

}

