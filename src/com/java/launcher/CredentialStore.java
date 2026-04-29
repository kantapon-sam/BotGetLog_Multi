package com.java.launcher;

import com.java.shared.AppMetadata;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Properties;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;

public final class CredentialStore {

    private static final String LEGACY_FILE_NAME = "launcher-site-settings.properties";
    private static final byte[] SALT = new byte[]{
        0x11, 0x2B, 0x3C, 0x4D, 0x5E, 0x06, 0x17, 0x28
    };
    private static final int ITERATION_COUNT = 41;

    private final File storeFile;

    public CredentialStore() {
        this.storeFile = AppMetadata.getLauncherCredentialStoreFile();
        migrateLegacyStoreIfNeeded();
    }

    public synchronized SiteCredential load(String siteId) {
        Properties properties = loadProperties();
        String username = properties.getProperty(siteId + ".username", "").trim();
        String encryptedPassword = properties.getProperty(siteId + ".password", "");
        String password = decryptPassword(encryptedPassword);
        boolean enabled = Boolean.parseBoolean(properties.getProperty(siteId + ".enabled", "false"));
        if (username.isEmpty() && password.isEmpty() && !enabled) {
            return null;
        }
        return new SiteCredential(siteId, username, password, enabled);
    }

    public synchronized void save(SiteCredential credential) {
        if (credential == null || credential.getSiteId().isEmpty()) {
            return;
        }

        Properties properties = loadProperties();
        String siteId = credential.getSiteId();
        properties.setProperty(siteId + ".username", credential.getUsername());
        properties.setProperty(siteId + ".password", encryptPassword(credential.getPassword()));
        properties.setProperty(siteId + ".enabled", Boolean.toString(credential.isAutoLoginEnabled()));
        storeProperties(properties);
    }

    public synchronized void clear(String siteId) {
        if (siteId == null || siteId.trim().isEmpty()) {
            return;
        }

        Properties properties = loadProperties();
        properties.remove(siteId + ".username");
        properties.remove(siteId + ".password");
        properties.remove(siteId + ".enabled");
        storeProperties(properties);
    }

    public synchronized boolean hasSavedCredential(String siteId) {
        SiteCredential credential = load(siteId);
        return credential != null && credential.hasUsableCredential();
    }

    private void migrateLegacyStoreIfNeeded() {
        if (storeFile.isFile()) {
            return;
        }

        File legacyStoreFile = new File(AppMetadata.getOutputDirectory(), LEGACY_FILE_NAME);
        if (!legacyStoreFile.isFile()) {
            return;
        }

        File parent = storeFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        try {
            Files.copy(legacyStoreFile.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    private Properties loadProperties() {
        Properties properties = new Properties();
        if (!storeFile.isFile()) {
            return properties;
        }

        try (FileInputStream input = new FileInputStream(storeFile)) {
            properties.load(input);
        } catch (IOException ignored) {
        }
        return properties;
    }

    private void storeProperties(Properties properties) {
        File parent = storeFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        try (FileOutputStream output = new FileOutputStream(storeFile)) {
            properties.store(output, "Bot Tool Launcher site settings");
        } catch (IOException ignored) {
        }
    }

    private String encryptPassword(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return "";
        }

        try {
            Cipher cipher = Cipher.getInstance("PBEWithMD5AndDES");
            cipher.init(Cipher.ENCRYPT_MODE, buildSecretKey(), buildParameterSpec());
            byte[] encrypted = cipher.doFinal(plainText.getBytes("UTF-8"));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception ex) {
            return "";
        }
    }

    private String decryptPassword(String encodedCipherText) {
        if (encodedCipherText == null || encodedCipherText.trim().isEmpty()) {
            return "";
        }

        try {
            Cipher cipher = Cipher.getInstance("PBEWithMD5AndDES");
            cipher.init(Cipher.DECRYPT_MODE, buildSecretKey(), buildParameterSpec());
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encodedCipherText));
            return new String(decrypted, "UTF-8");
        } catch (Exception ex) {
            return "";
        }
    }

    private SecretKey buildSecretKey() throws GeneralSecurityException {
        String machine = System.getenv("COMPUTERNAME");
        if (machine == null) {
            machine = "";
        }
        String seed = System.getProperty("user.name", "") + "|" + machine + "|BotToolLauncher";
        PBEKeySpec keySpec = new PBEKeySpec(seed.toCharArray());
        SecretKeyFactory keyFactory = SecretKeyFactory.getInstance("PBEWithMD5AndDES");
        return keyFactory.generateSecret(keySpec);
    }

    private PBEParameterSpec buildParameterSpec() {
        return new PBEParameterSpec(SALT, ITERATION_COUNT);
    }
}
