package com.java.botgetlog.truecorp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static com.java.botgetlog.truecorp.TrueCllsLoginCheck.Status.*;

public class TrueCllsLoginCheckRegression {
    public static void main(String[] args) throws Exception {
        String pair = "primary\nfixture-password\n";
        exchange("Username:Password:\r\n<CPE-TEST>", VERIFIED, pair);
        exchange("login:Password:\r\n*A:CPE-TEST#", VERIFIED, pair);
        exchange("Username:Password:\r\nCPE-TEST#", VERIFIED, pair);
        exchange("login:Password:\r\nprimary@CPE-TEST>", VERIFIED, pair);
        exchange("Username:Password:\r\nOTHER-CPE-TEST#", UNCONFIRMED, pair);
        exchange("Username:Password:\r\nCPE_TEST#", UNCONFIRMED, pair);
        exchange("Username:Password:\r\nLogin incorrect\r\nUsername:", REJECTED, pair);
        exchange("Username:Access denied\r\nUsername:", REJECTED, "primary\n");
        exchange("Username:Password:\r\nAccount locked\r\nUsername:", REJECTED, pair);
        exchange("Password:", UNCONFIRMED, "");
        exchange("CPE-TEST#", UNCONFIRMED, "");
        primaryStore();
        System.out.println("Login-only exchanges passed");
    }

    private static void primaryStore() throws Exception {
        Path dist = Files.createTempDirectory("clls-login-test");
        Path workbook = dist.resolve("UserInterface_Input.xlsx");
        Path store = dist.resolve("launcher-data/true-clls-credentials.properties");
        try {
            if (TrueCllsCredentialStore.readPrimaryForLoginCheck(workbook.toFile()).isComplete())
                throw new AssertionError("Absent primary store should allow workbook credentials");
            if (!TrueCllsCredentialStore.save(store.toFile(), "fixture-primary", "fixture-secret"))
                throw new AssertionError("Unable to create fixture store");
            TrueCllsCredentialStore.StoredCredential saved = TrueCllsCredentialStore.readPrimaryForLoginCheck(workbook.toFile());
            if (!"fixture-primary".equals(saved.username) || !"fixture-secret".equals(saved.password))
                throw new AssertionError("Login test must use the saved primary beside the selected workbook");
            Files.write(store, "clls.username=fixture-primary\nclls.password=invalid".getBytes(StandardCharsets.UTF_8));
            try {
                TrueCllsCredentialStore.readPrimaryForLoginCheck(workbook.toFile());
                throw new AssertionError("Unreadable primary must stop before contacting the gateway");
            } catch (java.io.IOException expected) { }
        } finally {
            Files.deleteIfExists(store); Files.deleteIfExists(store.getParent()); Files.delete(dist);
        }
    }

    private static void exchange(String received, TrueCllsLoginCheck.Status expected, String sent) throws Exception {
        try (Telnet_Multi.CredentialProbe probe = new Telnet_Multi.CredentialProbe()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Field in = probe.getClass().getDeclaredField("in"), out = probe.getClass().getDeclaredField("out");
            in.setAccessible(true); out.setAccessible(true);
            in.set(probe, new ByteArrayInputStream(received.getBytes(StandardCharsets.UTF_8)));
            out.set(probe, new PrintStream(output, true, "UTF-8"));
            TrueCllsLoginCheck.Status status = probe.checkPrimaryAtNode("CPE-TEST", "primary", "fixture-password");
            if (status != expected || !sent.equals(output.toString("UTF-8").replace("\r", "")))
                throw new AssertionError("Unexpected login exchange: " + received + " yielded " + status);
        }
    }
}
