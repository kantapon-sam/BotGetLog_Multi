package com.java.shared;

import java.nio.file.*;
import java.util.*;

public class ZteEnableSettingsRegression {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static Map<String, String> entry(String id, String password) { Map<String, String> result = new HashMap<String, String>(); result.put("id", id); result.put("password", password); return result; }
    interface Failure { void run() throws Exception; }
    static void fails(Failure action) throws Exception { try { action.run(); } catch (IllegalArgumentException | java.io.IOException expected) { return; } throw new AssertionError("Expected rejection"); }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("zte-settings-test-"); DeviceEnableSettings store = new DeviceEnableSettings(dir);
        check(store.loadZte().candidates().isEmpty(), "New installation must not supply passwords");
        check(Boolean.FALSE.equals(store.status().get("settingsSaved")), "Missing settings file");
        check(!Files.exists(dir.resolve("device-enable.enc")) && !Files.exists(dir.resolve("device-enable.key")), "Read must not create credentials");
        check(((List<?>) store.status().get("profiles")).size() == 1, "Only ZTE is configurable");
        fails(() -> store.save("ZTE", "missing", Arrays.asList(entry("0", "")), 2, false));
        Map<String, Object> initial = store.save("ZTE", "missing", Arrays.asList(entry("", "synthetic-primary"), entry("", "synthetic-backup")), 2, false);
        check(!initial.toString().contains("synthetic-primary"), "Status must not expose passwords");
        List<Map<String, String>> entries = Arrays.asList(entry("1", ""), entry("0", ""), entry("", "third #> secret"), entry("", "fourth"), entry("", "fifth"));
        Map<String, Object> saved = store.save("ZTE", (String) initial.get("revision"), entries, 2, false);
        check(store.loadZte().passwords.size() == 5 && store.loadZte().candidates().equals(Arrays.asList("synthetic-backup", "synthetic-primary")), "Reorder and attempt cap");
        String revision = (String) saved.get("revision");
        check("third #> secret".equals(store.reveal("ZTE", 2, revision)), "Read saved password");
        String encrypted = new String(Files.readAllBytes(dir.resolve("device-enable.enc")), "ISO-8859-1");
        check(!encrypted.contains("synthetic-primary") && !encrypted.contains("third #> secret"), "Passwords encrypted");
        fails(() -> store.save("ZTE", "missing", entries, 2, false));
        fails(() -> store.save("ZTE", revision, Arrays.asList(entry("", "bad\ncommand")), 2, false));
        fails(() -> store.save("ZTE", revision, Arrays.asList(entry("0", "same"), entry("1", "same")), 2, false));
        List<Map<String, String>> tooMany = new ArrayList<Map<String, String>>(entries); tooMany.add(entry("", "sixth"));
        fails(() -> store.save("ZTE", revision, tooMany, 2, false));
        fails(() -> store.save("HUAWEI", revision, entries, 2, false));
        check(!store.loadZte().redact("third #> secret and synthetic-primary").contains("secret"), "Redaction");
        store.save("ZTE", revision, Collections.emptyList(), 2, true);
        check(new DeviceEnableSettings(dir).loadZte().passwords.isEmpty(), "Clear must stay empty after reload");
        store.save("ZTE", (String) store.status().get("revision"), Arrays.asList(entry("", "synthetic-only")), 2, false);
        DeviceEnableSettings.Snapshot active = store.loadZte();
        Files.delete(dir.resolve("device-enable.enc"));
        check(store.loadZte().candidates().isEmpty(), "Deleting encrypted file must not restore any passwords");
        fails(() -> store.save("ZTE", revision, entries, 2, false));
        check(active.candidates().equals(Collections.singletonList("synthetic-only")), "In-flight snapshot unchanged");
        Files.delete(dir.resolve("device-enable.key"));
        check(store.loadZte().candidates().isEmpty(), "Deleting both files must leave no passwords");
        store.save("ZTE", "missing", Arrays.asList(entry("", "synthetic-new")), 1, false);
        byte[] savedKey = Files.readAllBytes(dir.resolve("device-enable.key"));
        Files.delete(dir.resolve("device-enable.key"));
        fails(() -> store.loadZte());
        Files.write(dir.resolve("device-enable.key"), savedKey);
        store.save("ZTE", (String) store.status().get("revision"), Collections.emptyList(), 2, false);
        check(store.loadZte().candidates().isEmpty(), "Removing every row must persist no passwords");
        byte[] corrupt = Files.readAllBytes(dir.resolve("device-enable.enc")); corrupt[15] ^= 1; Files.write(dir.resolve("device-enable.enc"), corrupt);
        fails(() -> store.loadZte());
        final int[] sentWithoutSettings = {0};
        fails(() -> ZteEnableFlow.enter(new ZteEnableFlow.Session() {
            public ZteEnableFlow.State current() { return ZteEnableFlow.State.USER; }
            public void command(String command) { sentWithoutSettings[0]++; }
            public void password(String password) { sentWithoutSettings[0]++; }
            public ZteEnableFlow.State read() { throw new AssertionError("Must not read with missing credentials"); }
            public void attempt(int number, boolean success) { }
        }, Collections.emptyList()));
        check(sentWithoutSettings[0] == 0, "Missing passwords must not send enable or credentials");
        flow(ZteEnableFlow.State.USER, Arrays.asList(ZteEnableFlow.State.PASSWORD, ZteEnableFlow.State.PRIVILEGED), 1, 1, true);
        flow(ZteEnableFlow.State.USER, Arrays.asList(ZteEnableFlow.State.PASSWORD, ZteEnableFlow.State.USER, ZteEnableFlow.State.PASSWORD, ZteEnableFlow.State.PRIVILEGED), 2, 2, true);
        flow(ZteEnableFlow.State.USER, Arrays.asList(ZteEnableFlow.State.PASSWORD, ZteEnableFlow.State.PASSWORD, ZteEnableFlow.State.PRIVILEGED), 2, 1, true);
        flow(ZteEnableFlow.State.USER, Arrays.asList(ZteEnableFlow.State.PASSWORD, ZteEnableFlow.State.PASSWORD, ZteEnableFlow.State.PASSWORD), 2, 1, false);
        flow(ZteEnableFlow.State.USER, Arrays.asList(ZteEnableFlow.State.PASSWORD, ZteEnableFlow.State.FAILED), 1, 1, false);
        flow(ZteEnableFlow.State.USER, Arrays.asList(ZteEnableFlow.State.PRIVILEGED), 0, 1, true);
        flow(ZteEnableFlow.State.PRIVILEGED, Collections.emptyList(), 0, 0, true);
        System.out.println("PASS ZTE settings: no embedded credentials, first save, clear, deletion, missing key, corruption, encryption, five slots, ordering, cap, stale writes, redaction and negotiation.");
    }
    static void flow(final ZteEnableFlow.State initial, List<ZteEnableFlow.State> responses, int passwords, int commands, boolean expected) throws Exception {
        final Iterator<ZteEnableFlow.State> stream = responses.iterator(); final List<String> sent = new ArrayList<String>(); final int[] enabled = {0};
        boolean result = ZteEnableFlow.enter(new ZteEnableFlow.Session() {
            public ZteEnableFlow.State current() { return initial; }
            public void command(String command) { check("enable".equals(command), "Unexpected command"); enabled[0]++; }
            public void password(String password) { sent.add(password); }
            public ZteEnableFlow.State read() { return stream.next(); }
            public void attempt(int number, boolean success) { }
        }, Arrays.asList("primary", "backup"));
        check(result == expected && sent.size() == passwords && enabled[0] == commands, "Negotiation sequence");
        if (passwords == 2) check(sent.equals(Arrays.asList("primary", "backup")), "Fallback order");
    }
}
