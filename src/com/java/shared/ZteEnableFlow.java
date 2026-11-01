package com.java.shared;

import java.io.IOException;
import java.util.List;

/** Bounded ZTE privilege negotiation. Never sends a password without its prompt. */
public final class ZteEnableFlow {
    public enum State { USER, PASSWORD, PRIVILEGED, FAILED }
    public interface Session {
        State current();
        void command(String command) throws IOException;
        void password(String password) throws IOException;
        State read() throws IOException;
        void attempt(int number, boolean success);
    }
    private ZteEnableFlow() { }
    public static boolean enter(Session session, List<String> passwords) throws IOException {
        return enter(session, passwords, false);
    }
    public static boolean enter(Session session, List<String> passwords, boolean explicitEnable) throws IOException {
        if (Thread.currentThread().isInterrupted()) return false;
        State state = session.current();
        if (state == State.PRIVILEGED && !explicitEnable) return true;
        if (state != State.USER && state != State.PRIVILEGED) return false;
        session.command("enable");
        state = session.read();
        if (state == State.PRIVILEGED) return true;
        if (state != State.PASSWORD) return false;
        if (passwords.isEmpty()) throw new IOException("ZTE requested an Enable password but none is saved. Save a password in Bot Settings - ZTE Enable.");
        int count = 0;
        for (String password : passwords) {
            if (Thread.currentThread().isInterrupted()) return false;
            if (state == State.USER) { session.command("enable"); state = session.read(); }
            if (state == State.PRIVILEGED) return true;
            if (state != State.PASSWORD) return false;
            session.password(password); count++;
            state = session.read();
            session.attempt(count, state == State.PRIVILEGED);
            if (state == State.PRIVILEGED) return true;
            if (state != State.USER && state != State.PASSWORD) return false;
        }
        return false;
    }
}
