package com.jcraft.jsch;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Local-only regression; package access allows fake JSch handles without a network connection. */
public final class SshGatewayCleanupRegression {

    private enum Stage {
        SESSION_CONNECT, OPEN_CHANNEL, PTY, PTY_TYPE, INPUT_STREAM, OUTPUT_STREAM, CHANNEL_CONNECT, SUCCESS
    }

    public static void main(String[] args) throws Exception {
        Method open = Class.forName("com.java.botgetlog.truecorp.Telnet_Multi")
                .getDeclaredMethod("openSshGatewaySession", Session.class);
        open.setAccessible(true);
        for (Stage stage : Stage.values()) {
            if (stage != Stage.SUCCESS) {
                verifyFailure(open, stage, false);
                verifyFailure(open, stage, true);
            }
        }
        verifySuccessfulHandoff(open);
        System.out.println("PASS SshGatewayCleanupRegression: 14 setup/cleanup failures and successful handoff");
    }

    private static void verifyFailure(Method open, Stage stage, boolean cleanupFails) throws Exception {
        Attempt attempt = new Attempt(stage, cleanupFails);
        try {
            open.invoke(null, attempt.session);
            throw new AssertionError("Expected setup failure at " + stage);
        } catch (InvocationTargetException failure) {
            check(failure.getCause() == attempt.failure, "Cleanup must preserve the setup failure at " + stage);
        }
        check(attempt.session.disconnected, "Session must disconnect after " + stage);
        check(attempt.channel.disconnected == (stage.ordinal() > Stage.OPEN_CHANNEL.ordinal()),
                "Every returned channel must disconnect after " + stage);
        check(attempt.inputClosed == (stage.ordinal() > Stage.INPUT_STREAM.ordinal()),
                "Every returned input stream must close after " + stage);
        check(attempt.outputClosed == (stage.ordinal() > Stage.OUTPUT_STREAM.ordinal()),
                "Every returned output stream must close after " + stage);
    }

    private static void verifySuccessfulHandoff(Method open) throws Exception {
        Attempt attempt = new Attempt(Stage.SUCCESS, false);
        Object handles = open.invoke(null, attempt.session);
        check(field(handles, "session") == attempt.session, "The successful session must reach its caller");
        check(field(handles, "channel") == attempt.channel, "The successful channel must reach its caller");
        check(field(handles, "inputStream") == attempt.input, "The successful input must reach its caller");
        PrintStream output = (PrintStream) field(handles, "outputStream");
        output.print("test");
        check(attempt.written == 4, "The successful output must remain writable");
        check(!attempt.session.disconnected && !attempt.channel.disconnected
                && !attempt.inputClosed && !attempt.outputClosed,
                "Successful connection resources must remain open for their caller");
        output.close();
        attempt.input.close();
        attempt.channel.disconnect();
        attempt.session.disconnect();
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Attempt {
        final Stage stage;
        final boolean cleanupFails;
        final RuntimeException failure = new IllegalStateException("simulated SSH setup failure");
        final FakeChannel channel;
        final FakeSession session;
        boolean inputClosed;
        boolean outputClosed;
        int written;

        final InputStream input = new InputStream() {
            @Override public int read() { return -1; }
            @Override public void close() throws IOException {
                inputClosed = true;
                if (cleanupFails) throw new IOException("simulated input close failure");
            }
        };
        final OutputStream output = new OutputStream() {
            @Override public void write(int value) { written++; }
            @Override public void close() throws IOException {
                outputClosed = true;
                if (stage != Stage.SUCCESS) {
                    check(session.disconnected, "Disconnect transport before closing output");
                }
                if (cleanupFails) throw new IOException("simulated output close failure");
            }
        };

        Attempt(Stage stage, boolean cleanupFails) throws JSchException {
            this.stage = stage;
            this.cleanupFails = cleanupFails;
            channel = new FakeChannel(this);
            session = new FakeSession(this);
        }

        void failAt(Stage current) {
            if (stage == current) throw failure;
        }
    }

    private static final class FakeSession extends Session {
        final Attempt attempt;
        boolean disconnected;

        FakeSession(Attempt attempt) throws JSchException {
            super(new JSch(), "test", "192.0.2.1", 22);
            this.attempt = attempt;
        }

        @Override public void connect(int timeout) { attempt.failAt(Stage.SESSION_CONNECT); }
        @Override public Channel openChannel(String type) {
            attempt.failAt(Stage.OPEN_CHANNEL);
            return attempt.channel;
        }
        @Override public void disconnect() {
            disconnected = true;
            if (attempt.cleanupFails) throw new IllegalStateException("simulated session cleanup failure");
        }
    }

    private static final class FakeChannel extends ChannelShell {
        final Attempt attempt;
        boolean disconnected;

        FakeChannel(Attempt attempt) { this.attempt = attempt; }

        @Override public void setPty(boolean enabled) { attempt.failAt(Stage.PTY); }
        @Override public void setPtyType(String type) { attempt.failAt(Stage.PTY_TYPE); }
        @Override public InputStream getInputStream() {
            attempt.failAt(Stage.INPUT_STREAM);
            return attempt.input;
        }
        @Override public OutputStream getOutputStream() {
            attempt.failAt(Stage.OUTPUT_STREAM);
            return attempt.output;
        }
        @Override public void connect(int timeout) { attempt.failAt(Stage.CHANNEL_CONNECT); }
        @Override public void disconnect() {
            disconnected = true;
            if (attempt.cleanupFails) throw new IllegalStateException("simulated channel cleanup failure");
        }
    }
}
