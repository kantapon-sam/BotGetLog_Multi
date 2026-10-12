package com.java.shared;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Shared on-disk contract for the collector, Windows settings and Control Console. */
public final class DeviceEnableSettings {
    public static final int MAX_PASSWORDS = 5;
    private final Path directory, file, key;
    private static final SecureRandom RANDOM = new SecureRandom();

    public DeviceEnableSettings(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        file = this.directory.resolve("device-enable.enc");
        key = this.directory.resolve("device-enable.key");
    }

    public static final class Snapshot {
        public final List<String> passwords;
        public final int maxAttempts;
        private Snapshot(List<String> passwords, int maxAttempts) {
            this.passwords = Collections.unmodifiableList(new ArrayList<String>(passwords));
            this.maxAttempts = maxAttempts;
        }
        public List<String> candidates() { return passwords.subList(0, Math.min(passwords.size(), maxAttempts)); }
        public String redact(String text) {
            if (text == null) return "";
            List<String> ordered = new ArrayList<String>(passwords);
            Collections.sort(ordered, (a, b) -> Integer.compare(b.length(), a.length()));
            for (String password : ordered) if (!password.isEmpty()) text = text.replace(password, "[REDACTED]");
            return text;
        }
    }
    private static final class Data {
        final Properties values; final String revision;
        Data(Properties values, String revision) { this.values = values; this.revision = revision; }
    }
    private static String vendor(String value) {
        if (!Collections.singletonList("ZTE").contains(value)) throw new IllegalArgumentException("Unknown vendor.");
        return value;
    }
    private static Properties defaults() {
        Properties p = new Properties();
        for (String v : Collections.singletonList("ZTE")) { p.setProperty(v + ".count", "0"); p.setProperty(v + ".attempts", "2"); }
        p.setProperty("ZTE.count", "2"); p.setProperty("ZTE.0", "zxr10"); p.setProperty("ZTE.1", "Zxr10_IPTN");
        return p;
    }
    private static Snapshot profile(Properties values, String v) throws IOException {
        try {
            int count = Integer.parseInt(values.getProperty(v + ".count"));
            int attempts = Integer.parseInt(values.getProperty(v + ".attempts"));
            if (count < 0 || count > MAX_PASSWORDS || attempts < 1 || attempts > MAX_PASSWORDS || ("ZTE".equals(v) && count == 0)) throw new IllegalArgumentException();
            List<String> passwords = new ArrayList<String>();
            for (int i = 0; i < count; i++) { String value = values.getProperty(v + "." + i); validatePassword(value); passwords.add(value); }
            return new Snapshot(passwords, attempts);
        } catch (RuntimeException ex) { throw new IOException("Invalid Enable settings. Restore or correct the saved settings."); }
    }
    public Snapshot loadZte() throws IOException { return profile(read().values, "ZTE"); }
    private Data read() throws IOException {
        if (!Files.exists(file)) return new Data(defaults(), "default");
        byte[] stored = Files.readAllBytes(file);
        try {
            if (stored.length < 29 || !Files.isRegularFile(key)) throw new GeneralSecurityException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Files.readAllBytes(key), "AES"), new GCMParameterSpec(128, Arrays.copyOfRange(stored, 0, 12)));
            Properties values = new Properties();
            values.load(new ByteArrayInputStream(cipher.doFinal(Arrays.copyOfRange(stored, 12, stored.length))));
            for (String v : Collections.singletonList("ZTE")) profile(values, v);
            return new Data(values, digest(stored));
        } catch (GeneralSecurityException | IllegalArgumentException ex) { throw new IOException("Cannot read Enable settings. The file or encryption key is unavailable."); }
    }
    public Map<String, Object> status() throws IOException { return status(read()); }
    private Map<String, Object> status(Data data) throws IOException {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("revision", data.revision); result.put("usingDefaults", "default".equals(data.revision));
        result.put("path", file.toString()); result.put("maxPasswords", MAX_PASSWORDS);
        List<Map<String, Object>> profiles = new ArrayList<Map<String, Object>>();
        for (String v : Collections.singletonList("ZTE")) {
            Snapshot snapshot = profile(data.values, v);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("vendor", v); item.put("supported", "ZTE".equals(v)); item.put("maxAttempts", snapshot.maxAttempts);
            List<Map<String, String>> entries = new ArrayList<Map<String, String>>();
            for (int i = 0; i < snapshot.passwords.size(); i++) { Map<String, String> row = new LinkedHashMap<String, String>(); row.put("id", String.valueOf(i)); entries.add(row); }
            item.put("entries", entries); profiles.add(item);
        }
        result.put("profiles", profiles); return result;
    }
    public String reveal(String v, int index, String revision) throws IOException {
        vendor(v); Data data = read(); checkRevision(revision, data.revision);
        List<String> passwords = profile(data.values, v).passwords;
        if (index < 0 || index >= passwords.size()) throw new IllegalArgumentException("Invalid password slot.");
        return passwords.get(index);
    }
    public Map<String, Object> save(String v, String revision, List<Map<String, String>> entries, int maxAttempts, boolean reset) throws IOException {
        vendor(v); Files.createDirectories(directory);
        Path lockPath = directory.resolve("device-enable.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            restrict(lockPath);
            try (FileLock lock = channel.tryLock()) {
                if (lock == null) throw new IllegalArgumentException("Enable settings are being saved. Refresh and retry.");
                Data data = read(); checkRevision(revision, data.revision);
                List<String> current = profile(data.values, v).passwords, next = new ArrayList<String>();
                if (reset) { Snapshot initial = profile(defaults(), v); next.addAll(initial.passwords); maxAttempts = initial.maxAttempts; }
                else {
                    if (entries == null || entries.size() > MAX_PASSWORDS) throw new IllegalArgumentException("Store up to five passwords per vendor.");
                    Set<String> usedIds = new HashSet<String>();
                    for (Map<String, String> entry : entries) {
                        if (entry == null) throw new IllegalArgumentException("Invalid password entry.");
                        String value = entry.get("password"), id = entry.get("id");
                        if (id != null && !id.isEmpty() && !usedIds.add(id)) throw new IllegalArgumentException("Duplicate password slot.");
                        if (value == null || value.isEmpty()) {
                            try { value = current.get(Integer.parseInt(id)); }
                            catch (RuntimeException ex) { throw new IllegalArgumentException("Enter a password for every new slot."); }
                        }
                        validatePassword(value);
                        if (next.contains(value)) throw new IllegalArgumentException("Passwords must be different.");
                        next.add(value);
                    }
                }
                if ("ZTE".equals(v) && next.isEmpty()) throw new IllegalArgumentException("ZTE requires a primary password.");
                if (maxAttempts < 1 || maxAttempts > MAX_PASSWORDS) throw new IllegalArgumentException("Choose between one and five attempts.");
                for (int i = 0; i < MAX_PASSWORDS; i++) data.values.remove(v + "." + i);
                data.values.setProperty(v + ".count", String.valueOf(next.size()));
                data.values.setProperty(v + ".attempts", String.valueOf(maxAttempts));
                for (int i = 0; i < next.size(); i++) data.values.setProperty(v + "." + i, next.get(i));
                write(data.values);
                return status();
            } catch (OverlappingFileLockException ex) { throw new IllegalArgumentException("Enable settings are being saved. Refresh and retry."); }
        }
    }
    private static void validatePassword(String value) {
        if (value == null || value.isEmpty() || value.length() > 128) throw new IllegalArgumentException("Passwords must contain 1 to 128 characters.");
        for (char ch : value.toCharArray()) if (Character.isISOControl(ch)) throw new IllegalArgumentException("Passwords cannot contain line breaks or control characters.");
    }
    private static void checkRevision(String expected, String actual) {
        if (!actual.equals(expected)) throw new IllegalArgumentException("Settings changed. Refresh before saving or showing a password.");
    }
    private void write(Properties values) throws IOException {
        try {
            if (!Files.exists(key)) { byte[] bytes = new byte[16]; RANDOM.nextBytes(bytes); atomicWrite(key, bytes); }
            byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Files.readAllBytes(key), "AES"), new GCMParameterSpec(128, iv));
            ByteArrayOutputStream plain = new ByteArrayOutputStream(); values.store(plain, "Device Enable settings");
            ByteArrayOutputStream sealed = new ByteArrayOutputStream(); sealed.write(iv); sealed.write(cipher.doFinal(plain.toByteArray()));
            atomicWrite(file, sealed.toByteArray());
        } catch (GeneralSecurityException ex) { throw new IOException("Unable to encrypt Enable settings."); }
    }
    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".enable-", ".tmp");
        try {
            restrict(temp); Files.write(temp, bytes);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    private static void restrict(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); return; }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) acl.setAcl(Collections.singletonList(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner()).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }
    private static String digest(byte[] bytes) throws IOException {
        try { StringBuilder hex = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format("%02x", b & 255)); return hex.toString(); }
        catch (NoSuchAlgorithmException ex) { throw new IOException("SHA-256 unavailable."); }
    }
}
