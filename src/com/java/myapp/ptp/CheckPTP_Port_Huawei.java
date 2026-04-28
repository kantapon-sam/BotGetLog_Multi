package com.java.myapp.ptp;

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
                // หยุดเมื่อเจอ Time Performance หรือเส้นขีดใหม่
                if (line.contains("Time Performance")) {
                    break;
                }
                if (insideTable && !line.trim().isEmpty() && !line.contains("----")) {
                    // ตัดบรรทัดด้วยช่องว่าง (regex \\s+)
                    String[] parts = line.trim().split("\\s+");
                    // join เป็น CSV
                   // System.out.println(String.join(",", parts));
                    
                     Str_PTP += "\n" + hostname[0] + "," + String.join(",", parts);
                }
            }

        }

        if (path.contains("PTP")) {
            System.out.println("Done " + path);
        }
        br.close();

        return Str_PTP;
    }

}
