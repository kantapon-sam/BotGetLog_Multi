package com.java.launcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

final class PulseSecureAutomation {

    private static final File PULSE_UI_EXE = new File("C:\\Program Files (x86)\\Common Files\\Pulse Secure\\JamUI\\Pulse.exe");

    private PulseSecureAutomation() {
    }

    static boolean isAvailable() {
        return PULSE_UI_EXE.isFile();
    }

    static ConnectResult openPulse() {
        if (!isAvailable()) {
            return ConnectResult.error("Pulse Secure client was not found on this machine.");
        }

        if (!showPulseUi()) {
            return ConnectResult.error("Unable to open Pulse Secure automatically.");
        }
        if (!activatePulseWindow()) {
            return ConnectResult.error(
                    "Pulse Secure was opened, but the window could not be brought to the front automatically."
                    + " Please switch to Pulse Secure and connect there."
            );
        }
        return ConnectResult.info("Pulse Secure is open. Choose the connection you want and click Connect there.");
    }

    private static void drainProcess(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            while (reader.readLine() != null) {
                // Drain output to prevent the child process from blocking on a full pipe.
            }
        }
    }

    private static boolean showPulseUi() {
        if (!PULSE_UI_EXE.isFile()) {
            return false;
        }
        try {
            new ProcessBuilder(PULSE_UI_EXE.getAbsolutePath(), "-show").start();
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private static boolean activatePulseWindow() {
        Process process = null;
        try {
            String command =
                    "$ws = New-Object -ComObject WScript.Shell; "
                    + "if ($ws.AppActivate('Pulse Secure')) { Start-Sleep -Milliseconds 300; exit 0 } "
                    + "if ($ws.AppActivate('Pulse')) { Start-Sleep -Milliseconds 300; exit 0 } "
                    + "exit 1";
            process = new ProcessBuilder(
                    "powershell",
                    "-NoProfile",
                    "-STA",
                    "-Command",
                    command
            ).redirectErrorStream(true).start();
            drainProcess(process);
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    static final class ConnectResult {

        private final boolean success;
        private final String message;

        private ConnectResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        static ConnectResult info(String message) {
            return new ConnectResult(true, message);
        }

        static ConnectResult error(String message) {
            return new ConnectResult(false, message);
        }

        boolean isSuccess() {
            return success;
        }

        String getMessage() {
            return message;
        }
    }
}
