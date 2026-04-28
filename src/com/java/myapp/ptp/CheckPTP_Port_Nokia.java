package com.java.myapp.ptp;

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
                    insideTable = true; // เริ่มอ่านข้อมูล
                    continue;
                }
                if (insideTable) {
                    // ข้ามเส้นแบ่งหรือบรรทัดว่าง
                    if (line.isEmpty() || line.startsWith("--") || line.startsWith("No. of PTP")) {
                        continue;
                    }

                    if (line.startsWith("=")) {
                        break; // จบบล็อก
                    }

                    // แยกและพิมพ์ด้วย comma
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 6) {
                        System.out.println(String.join(",", parts));
                        parts[0] = "'" + parts[0];
                        Str_PTP += "\n" + hostname[0] + "," + String.join(",", parts);
                    }
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
