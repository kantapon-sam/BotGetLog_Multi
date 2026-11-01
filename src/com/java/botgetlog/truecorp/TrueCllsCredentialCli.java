package com.java.botgetlog.truecorp;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class TrueCllsCredentialCli {

    private TrueCllsCredentialCli() {
    }

    public static void main(String[] args) throws Exception {
        int backupSlot = backupSlot(args);
        boolean stdin = hasArg(args, "--stdin");
        String username = stdin ? "" : value(System.getenv("TRUE_CLLS_USERNAME"));
        String password = stdin ? "" : value(System.getenv("TRUE_CLLS_PASSWORD"));

        if (hasArg(args, "--stdin") || username.isEmpty() || password.isEmpty()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
            if (username.isEmpty()) {
                username = value(reader.readLine());
            }
            if (password.isEmpty()) {
                password = value(reader.readLine());
            }
        }

        if (username.isEmpty() || password.isEmpty()) {
            System.err.println("[ERROR] TRUE CLLS username/password are required.");
            System.exit(2);
        }

        if (backupSlot > 0) {
            if (!TrueCllsCredentialStore.saveBackup(backupSlot, username, password)) {
                System.err.println("[ERROR] Cannot save TRUE CLLS backup slot " + backupSlot + ".");
                System.exit(1);
            }
            boolean verified = false;
            for (CllsFallback.Credential credential : TrueCllsCredentialStore.loadBackups())
                if (credential.slot == backupSlot && credential.username.equals(username)
                        && credential.password.equals(password)) verified = true;
            if (!verified) {
                System.err.println("[ERROR] Saved TRUE CLLS backup could not be verified.");
                System.exit(1);
            }
            System.out.println("[OK] TRUE CLLS backup slot=" + backupSlot + " configured=true");
            return;
        }

        if (!TrueCllsCredentialStore.save(username, password)) {
            System.err.println("[ERROR] Cannot save TRUE CLLS credentials.");
            System.exit(1);
        }

        TrueCllsCredentialStore.StoredCredential loaded = TrueCllsCredentialStore.load();
        if (!loaded.isComplete() || !username.equals(loaded.username) || !password.equals(loaded.password)) {
            System.err.println("[ERROR] Saved TRUE CLLS credentials could not be verified.");
            System.exit(1);
        }

        System.out.println("[OK] TRUE CLLS credentials saved and verified: "
                + TrueCllsCredentialStore.getStoreFile().getAbsolutePath());
    }

    static int backupSlot(String[] args) {
        int slot = 0;
        if (args == null) return slot;
        for (String arg : args) if (arg != null && arg.startsWith("--backup-slot=")) {
            if (slot != 0) throw new IllegalArgumentException("Specify one CLLS backup slot.");
            slot = Integer.parseInt(arg.substring("--backup-slot=".length()));
            if (slot < 1 || slot > CllsFallback.MAX_BACKUPS)
                throw new IllegalArgumentException("CLLS backup slot must be between 1 and 5.");
        }
        return slot;
    }

    private static boolean hasArg(String[] args, String expected) {
        if (args == null || expected == null) {
            return false;
        }
        for (String arg : args) {
            if (expected.equalsIgnoreCase(value(arg))) {
                return true;
            }
        }
        return false;
    }

    private static String value(String text) {
        return text == null ? "" : text.trim();
    }
}
