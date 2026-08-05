package com.java.tools.linkoptical;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashSet;
import java.util.Set;

public final class LinkOpticalCpuMemoryRegression {

    private static final String EXPECTED_HEADER
            = "Site code,IP loopback,CPU Current (%),CPU Idle (%),"
            + "Memory Total (MB),Memory Used (MB),Memory Free (MB),"
            + "Memory Used (%),Memory Free (%)";

    private LinkOpticalCpuMemoryRegression() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: <output-dir> <input-log> [input-log ...]");
        }
        File outputDir = new File(args[0]);
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) {
            throw new IllegalStateException("Cannot create output directory: " + outputDir);
        }
        File[] inputs = new File[args.length - 1];
        for (int i = 1; i < args.length; i++) {
            inputs[i - 1] = new File(args[i]);
            if (!inputs[i - 1].isFile()) {
                throw new IllegalArgumentException("Input file not found: " + inputs[i - 1]);
            }
        }

        Link_Optical.ProcessResult result
                = Link_Optical.processFiles(inputs, outputDir, false);
        File cpuMemory = result.getCpuMemoryFile();
        if (cpuMemory == null || !cpuMemory.isFile()) {
            throw new AssertionError("DataCPU_Memory output was not generated");
        }
        if (result.getOutputFiles().size() != 5) {
            throw new AssertionError(
                    "Expected five Link Optical outputs, got "
                    + result.getOutputFiles().size());
        }

        int rows = 0;
        Set<String> siteCodes = new HashSet<String>();
        try (BufferedReader reader = new BufferedReader(new FileReader(cpuMemory))) {
            String header = reader.readLine();
            if (!EXPECTED_HEADER.equals(header)) {
                throw new AssertionError("Unexpected CPU/Memory header: " + header);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] columns = line.split(",", -1);
                if (columns.length != 9) {
                    throw new AssertionError("Expected 9 columns: " + line);
                }
                for (String column : columns) {
                    if (column.trim().isEmpty()) {
                        throw new AssertionError("Blank CPU/Memory value: " + line);
                    }
                }
                siteCodes.add(columns[0]);
                rows++;
            }
        }
        if (rows != inputs.length) {
            throw new AssertionError(
                    "Expected " + inputs.length + " CPU/Memory rows, got " + rows);
        }
        for (String siteCode : siteCodes) {
            if (siteCode.matches("^\\*?[A-Za-z]:.*")) {
                throw new AssertionError("Unnormalized Site code: " + siteCode);
            }
        }
        System.out.println("CPU_MEMORY_REGRESSION=PASS outputs=5 rows=" + rows
                + " columns=9 file=" + cpuMemory.getAbsolutePath());
    }
}
