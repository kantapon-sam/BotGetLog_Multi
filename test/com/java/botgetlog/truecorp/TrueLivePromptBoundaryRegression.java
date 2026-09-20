package com.java.botgetlog.truecorp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/** Prevent Nokia description text ending in # from being treated as the device prompt. */
public final class TrueLivePromptBoundaryRegression {

    private TrueLivePromptBoundaryRegression() {
    }

    public static void main(String[] args) throws Exception {
        String devicePrompt = "B:RN-KKN0030-3_KKNKKN5110W#";
        assertCompleteResponse(devicePrompt, devicePrompt);
        assertCompleteResponse(devicePrompt, "*A:RN-KKN0030-3_KKNKKN5110W#");
        System.out.println("TRUE_LIVE_PROMPT_BOUNDARY_OK");
    }

    private static void assertCompleteResponse(String establishedPrompt, String returnedPrompt) throws Exception {
        String response = " show port 1/1/c7/1 ethernet lldp remote-info\r\n"
                + "                        Tie#\r\n"
                + "61,62;01/03/04-CORE-ODF#\r\n"
                + "System Name : RN-PEER\r\n"
                + returnedPrompt + "\r\n";

        Class<?> type = Class.forName("com.java.botgetlog.truecorp.Telnet_Multi$CredentialProbe");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object probe = constructor.newInstance();
        field(type, "in").set(probe, new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)));
        field(type, "out").set(probe, new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"));
        field(type, "lastPromptToken").set(probe, establishedPrompt);

        Method read = type.getDeclaredMethod("readUntilPromptOnlyLarge", String[].class);
        read.setAccessible(true);
        String actual = (String) read.invoke(probe, (Object) new String[]{"#", ">"});
        if (!actual.contains("System Name : RN-PEER") || !actual.contains(returnedPrompt)) {
            throw new AssertionError("Description text was mistaken for the prompt: " + actual.replace('\r', ' '));
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field value = type.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }
}
