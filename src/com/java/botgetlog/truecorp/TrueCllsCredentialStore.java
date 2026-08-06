package com.java.botgetlog.truecorp;

import com.java.shared.AppMetadata;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

final class TrueCllsCredentialStore {

    private static final String STORE_FILE_NAME = "true-clls-credentials.properties";
    private static final String KEY_USERNAME = "clls.username";
    private static final String KEY_PASSWORD = "clls.password";
    private static final String ENCRYPTION_PREFIX = "v1";
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 16;
    private static final int ITERATION_COUNT = 65536;
    private static final int KEY_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TrueCllsCredentialStore() {
    }

    static final class StoredCredential {

        final String username;
        final String password;

        StoredCredential(String username, String password) {
            this.username = safeValue(username);
            this.password = safeValue(password);
        }

        boolean isComplete() {
            return !username.isEmpty() && !password.isEmpty();
        }
    }

    static StoredCredential load() {
        Properties properties = loadProperties();
        String username = properties.getProperty(KEY_USERNAME, "").trim();
        String encryptedPassword = properties.getProperty(KEY_PASSWORD, "").trim();
        String password = decryptPassword(encryptedPassword);
        return new StoredCredential(username, password);
    }

    static boolean save(String username, String password) {
        String safeUsername = safeValue(username);
        String safePassword = safeValue(password);
        if (safeUsername.isEmpty() || safePassword.isEmpty()) {
            return false;
        }

        String encryptedPassword = encryptPassword(safePassword);
        if (encryptedPassword.isEmpty()) {
            return false;
        }

        Properties properties = new Properties();
        properties.setProperty(KEY_USERNAME, safeUsername);
        properties.setProperty(KEY_PASSWORD, encryptedPassword);
        File storeFile = getStoreFile();
        File parent = storeFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        try (FileOutputStream output = new FileOutputStream(storeFile)) {
            properties.store(output, "TRUE CLLS credentials. Password is encrypted for this Windows user/machine.");
            return true;
        } catch (Exception e) {
            System.out.println("[WARN] Cannot save TRUE CLLS credentials: " + e.getMessage());
            return false;
        }
    }

    static File getStoreFile() {
        return new File(AppMetadata.getLauncherDataDirectory(), STORE_FILE_NAME);
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        File storeFile = getStoreFile();
        if (!storeFile.isFile()) {
            return properties;
        }

        try (FileInputStream input = new FileInputStream(storeFile)) {
            properties.load(input);
        } catch (Exception e) {
            System.out.println("[WARN] Cannot load TRUE CLLS credential store: " + e.getMessage());
        }
        return properties;
    }

    private static String encryptPassword(String plainText) {
        try {
            byte[] salt = new byte[SALT_BYTES];
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(salt);
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(salt), new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes("UTF-8"));

            return ENCRYPTION_PREFIX + ":"
                    + Base64.getEncoder().encodeToString(salt) + ":"
                    + Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            System.out.println("[WARN] Cannot encrypt TRUE CLLS password: " + e.getMessage());
            return "";
        }
    }

    private static String decryptPassword(String encryptedValue) {
        String value = safeValue(encryptedValue);
        if (value.isEmpty()) {
            return "";
        }

        try {
            String[] parts = value.split(":", 4);
            if (parts.length != 4 || !ENCRYPTION_PREFIX.equals(parts[0])) {
                return "";
            }

            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] iv = Base64.getDecoder().decode(parts[2]);
            byte[] encrypted = Base64.getDecoder().decode(parts[3]);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(salt), new IvParameterSpec(iv));
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, "UTF-8");
        } catch (Exception e) {
            System.out.println("[WARN] Cannot decrypt TRUE CLLS password. Please re-enter it.");
            return "";
        }
    }

    private static SecretKeySpec deriveKey(byte[] salt) throws GeneralSecurityException {
        SecretKeyFactory factory;
        try {
            factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        } catch (NoSuchAlgorithmException e) {
            factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        }
        PBEKeySpec keySpec = new PBEKeySpec(buildMachineSeed().toCharArray(), salt, ITERATION_COUNT, KEY_BITS);
        byte[] keyBytes = factory.generateSecret(keySpec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static String buildMachineSeed() {
        String computerName = safeValue(System.getenv("COMPUTERNAME"));
        String userDomain = safeValue(System.getenv("USERDOMAIN"));
        String userName = safeValue(System.getProperty("user.name", ""));
        return userName + "|" + userDomain + "|" + computerName + "|" + AppMetadata.getAppName() + "|TRUE-CLLS";
    }

    private static String safeValue(String value) {
        return value == null ? "" : value.trim();
    }
}
