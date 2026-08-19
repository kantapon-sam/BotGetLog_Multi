package com.java.tools.linkoptical;

import java.io.File;

public final class LinkOpticalSingleFileProbe {

    private LinkOpticalSingleFileProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: <input-log> <output-dir>");
        }

        File input = new File(args[0]);
        File output = new File(args[1]);
        if (!input.isFile()) {
            throw new IllegalArgumentException("Input file not found: " + input);
        }
        if (!output.isDirectory() && !output.mkdirs()) {
            throw new IllegalStateException("Cannot create output directory: " + output);
        }

        Link_Optical.ProcessResult result = Link_Optical.processFiles(
                new File[]{input}, output, false);
        System.out.println("PROBE_TOTAL_FILES=" + result.getTotalFiles());
        System.out.println("PROBE_FULL_LLDP=" + pathOf(result.getFullLldpFile()));
        System.out.println("PROBE_NEIGHBOR=" + pathOf(result.getNeighborFile()));
        System.out.println("PROBE_PORT=" + pathOf(result.getPortFile()));
        System.out.println("PROBE_DESCRIPTION=" + pathOf(result.getDescriptionFile()));
    }

    private static String pathOf(File file) {
        return file == null ? "" : file.getAbsolutePath();
    }
}
