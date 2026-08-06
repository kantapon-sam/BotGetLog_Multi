package com.java.botgetlog.truecorp;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class TrueCllsCredentialCli {

    private TrueCllsCredentialCli() {
    }

    public static void main(String[] args) throws Exception {
        String username = value(System.getenv("TRUE_CLLS_USERNAME"));
        String password = value(System.getenv("TRUE_CLLS_PASSWORD"));

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
