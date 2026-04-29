package com.java.launcher;

import javax.swing.JOptionPane;

public final class AutoLoginHelperMain {

    private AutoLoginHelperMain() {
    }

    public static void main(String[] args) {
        if (args == null || args.length < 3) {
            JOptionPane.showMessageDialog(
                    null,
                    "Auto Login helper arguments are missing.",
                    "Auto Login",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        String siteId = args[0];
        String siteLabel = args[1];
        String targetUrl = args[2];

        CredentialStore credentialStore = new CredentialStore();
        SiteCredential credential = credentialStore.load(siteId);
        if (credential == null || !credential.hasUsableCredential()) {
            JOptionPane.showMessageDialog(
                    null,
                    "Saved credential not found for " + siteLabel,
                    "Auto Login",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        LoginRecipe recipe = new LoginRecipeRegistry().getRecipe(siteId);
        new ChromeAutoLoginService(siteLabel, targetUrl, credential, recipe).run();
    }
}
