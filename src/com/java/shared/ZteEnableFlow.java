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
        State state = session.current();
        if (state == State.PRIVILEGED) return true;
        if (passwords.isEmpty()) throw new IOException("No ZTE Enable passwords saved. Save a password in Bot Settings - ZTE Enable before collecting ZTE logs.");
        if (state != State.USER) return false;
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
