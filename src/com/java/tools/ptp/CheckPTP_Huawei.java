package com.java.tools.ptp;

import java.io.BufferedReader;
import java.io.IOException;

public class CheckPTP_Huawei {

    private static String valueForLabel(String line, String label) {
        int labelIndex = line.indexOf(label);
        if (labelIndex < 0) {
            return "";
        }

        int colonIndex = line.indexOf(':', labelIndex + label.length());
        if (colonIndex < 0) {
            return "";
        }

        String value = line.substring(colonIndex + 1).trim();
        if (value.isEmpty()) {
            return "";
        }

        return value.split("\\s+")[0];
    }

    public static String Sub(BufferedReader br, String path) throws IOException {
        String line;
        String Str_all_PTP = "";
        String Str_PTP = "";

        String hostname[] = new String[10000];
        int h = 0;
        String Parent_id = "";
        String Grandmaster_id = "";
        String Timescale = "";
        String Domain_value = "";
        String Steps_removed = "";
        String Realtime = "";
        String Max = "";
        String Min = "";
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
                if (line.contains("Parent clock ID")) {
                    Parent_id = valueForLabel(line, "Parent clock ID");
                }
                if (line.contains("Grand clock ID")) {
                    Grandmaster_id = valueForLabel(line, "Grand clock ID");
                }
                if (line.contains("Timescale")) {
                    Timescale = valueForLabel(line, "Timescale");
                }
                if (line.contains("Domain  value")) {
                    Domain_value = valueForLabel(line, "Domain  value");
                }
                if (line.contains("Step removed")) {
                    Steps_removed = valueForLabel(line, "Step removed");
                }
                if (line.contains("Realtime(T2-T1)")) {
                    Realtime = valueForLabel(line, "Realtime(T2-T1)");
                }
                if (line.contains("Max(T2-T1)")) {
                    Max = valueForLabel(line, "Max(T2-T1)");
                }
                if (line.contains("Min(T2-T1)")) {
                    Min = valueForLabel(line, "Min(T2-T1)");
                }
                Str_PTP = "\n" + hostname[0] + "," + Parent_id + "," + Grandmaster_id + "," + Timescale + "," + Domain_value + "," + Steps_removed + "," + Realtime + "," + Max + "," + Min;
            }

        }

        if (path.contains("PTP")) {
            System.out.println("Done " + path);
        }
        br.close();

        return Str_PTP;
    }

}

