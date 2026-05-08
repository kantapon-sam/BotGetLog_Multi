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
                    insideTable = true;
                    continue;
                }
                if (insideTable) {
                    if (line.isEmpty() || line.startsWith("--") || line.startsWith("No. of PTP")) {
                        continue;
                    }

                    if (line.startsWith("=")) {
                        break;
                    }

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

