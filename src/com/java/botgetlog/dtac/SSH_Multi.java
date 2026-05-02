package com.java.botgetlog.dtac;

import com.jcraft.jsch.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// ====== เพิ่มมาสำคัญสำหรับ BouncyCastle ======
import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
// ============================================

/**
 * ทำงานแบบ instance ละ 1 โหนด (1 row + 1 cmdSet)
 * Constructor จะ:
 * - อ่าน cmd จาก sheet cmdSet ตามชื่อ cmdSet
 * - SSH เข้า Loopback
 * - ยิงทุก command (ผ่าน shell interactive เหมือน Telnet)
 * - เขียน log ต่อโหนด (ไฟล์ text)
 * - เขียน logWork
 */
public class SSH_Multi {

    private final LocalDateTime now = LocalDateTime.now();
    private final DateTimeFormatter formatterLOG = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final String formattedDateTimeLOG = now.format(formatterLOG);

// ====== TIMEOUT/RETRY CONFIG ======
private static final int SSH_PORT = 22;
private static final int TCP_PRECHECK_TIMEOUT_MS = 3000;      // เช็ค port 22 ก่อนลอง SSH
private static final int CONNECT_TIMEOUT_MS = 30000;          // session.connect()
private static final int SESSION_SOCKET_TIMEOUT_MS = 60000;   // socket read timeout หลัง connect
private static final int CHANNEL_TIMEOUT_MS = 20000;          // channel.connect()
private static final int MAX_RETRY = 2;                       // retry ต่อโหนด (เฉพาะ timeout/ชั่วคราว)
private static final int RETRY_BACKOFF_MS = 2500;             // หน่วงก่อน retry (คูณด้วย attempt)
private static final int FIRST_PROMPT_TIMEOUT_MS = 4000;      // รอ prompt แรกหลังเข้า shell
private static final int FIRST_PROMPT_IDLE_BREAK_MS = 350;    // ไม่มีข้อมูลใหม่กี่ ms ให้ตัดอ่าน prompt แรก
private static final int COMMAND_PROMPT_TIMEOUT_MS = 30000;   // รอ prompt กลับหลังยิง command
private static final int PROMPT_WINDOW_CHARS = 600;           // buffer ท้ายไว้ใช้ detect prompt
private static final int EXCEL_CACHE_OPEN_MAX_RETRY = 3;
private static final long EXCEL_CACHE_OPEN_RETRY_DELAY_MS = 1500L;
private static final String CMDSET_SHEET = "cmdSet";
private static final Object CONSOLE_LOG_LOCK = new Object();
private static final Map<String, List<String>> CMDSET_CACHE = new ConcurrentHashMap<>();
private static final Object CMDSET_CACHE_LOCK = new Object();
private static volatile boolean CMDSET_CACHE_READY = false;
    private final PathFile fileInput = new PathFile();
    private Session session;

    public static final class CredentialValidationResult {
        public final boolean success;
        public final boolean authFailure;
        public final String message;

        CredentialValidationResult(boolean success, boolean authFailure, String message) {
            this.success = success;
            this.authFailure = authFailure;
            this.message = (message == null || message.trim().isEmpty()) ? "-" : message.trim();
        }
    }

    // ====== STATIC BLOCK: init BouncyCastle + dhgex ======
// ====== STATIC BLOCK: init BouncyCastle + kex/hostkey เก่า/ใหม่ ======
// ====== STATIC BLOCK: init BouncyCastle + kex/hostkey/cipher เก่า/ใหม่ ======
static {
    try {
        // ให้ BouncyCastle อยู่ลำดับแรก ๆ
        if (Security.getProvider("BC") == null) {
            Security.insertProviderAt(new BouncyCastleProvider(), 1);
        }

        // ---- KEX รองรับทั้ง sha1 (เก่า) และ sha256/curve/ecdh (ใหม่) ----
        String kex =
                "diffie-hellman-group-exchange-sha1," +
                "diffie-hellman-group14-sha1," +
                "diffie-hellman-group1-sha1," +  // สำหรับโหนดเก่ามาก ๆ
                "diffie-hellman-group-exchange-sha256," +
                "diffie-hellman-group14-sha256," +
                "curve25519-sha256,curve25519-sha256@libssh.org," +
                "ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521";
        JSch.setConfig("kex", kex);

        // ---- CIPHER: เพิ่ม CBC ให้ตรงกับ serverProposal (aes128-cbc,3des-cbc,des-cbc) ----
        String ciphers =
                "aes128-ctr,aes192-ctr,aes256-ctr," +  // ของใหม่
                "aes128-cbc,3des-cbc,des-cbc";          // ของเก่า (ที่ node 10.242.9.110 ใช้)

        JSch.setConfig("cipher.c2s", ciphers); // client → server
        JSch.setConfig("cipher.s2c", ciphers); // server → client

        // ---- HOST KEY: เพิ่มแบบเก่าเข้าไป (ssh-rsa / ssh-dss) ----
        String current = JSch.getConfig("server_host_key");
        if (current == null || current.isEmpty()) {
            current = "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384," +
                      "ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256";
        }
        String updated = current + ",ssh-rsa,ssh-dss";
        JSch.setConfig("server_host_key", updated);

    } catch (Throwable t) {
        System.out.println("[WARN] Cannot init BouncyCastle / kex/cipher: " + t);
    }
}

// =====================================================

    // =====================================================

    public static CredentialValidationResult validateCredentials(String host, String user, String pass) {
        String targetHost = host == null ? "" : host.trim();
        String targetUser = user == null ? "" : user.trim();

        if (targetHost.isEmpty()) {
            return new CredentialValidationResult(false, false, "Target IP is empty");
        }
        if (targetUser.isEmpty() || pass == null || pass.trim().isEmpty()) {
            return new CredentialValidationResult(false, true, "Username or password is empty");
        }

        if (!isTcpPortOpen(targetHost, SSH_PORT, TCP_PRECHECK_TIMEOUT_MS)) {
            return new CredentialValidationResult(false, false,
                    "TCP port " + SSH_PORT + " unreachable (timeout/refused) for " + targetHost);
        }

        Session testSession = null;
        try {
            JSch jsch = new JSch();
            java.util.Hashtable<String, String> baseCfg = new java.util.Hashtable<>(JSch.getConfig());
            jsch.setConfig(baseCfg);

            testSession = jsch.getSession(targetUser, targetHost, SSH_PORT);
            testSession.setPassword(pass);

            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            config.put("PreferredAuthentications", "password,keyboard-interactive,publickey");
            testSession.setConfig(config);

            testSession.connect(CONNECT_TIMEOUT_MS);
            testSession.setTimeout(SESSION_SOCKET_TIMEOUT_MS);
            return new CredentialValidationResult(true, false, "Credential OK");
        } catch (JSchException ex) {
            String message = ex.getMessage();
            return new CredentialValidationResult(false, isAuthenticationFailure(message), message);
        } catch (Exception ex) {
            return new CredentialValidationResult(false, false, ex.getMessage());
        } finally {
            try {
                if (testSession != null && testSession.isConnected()) {
                    testSession.disconnect();
                }
            } catch (Exception ignore) { }
        }
    }

    private static boolean isAuthenticationFailure(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("auth fail")
                || lower.contains("authentication failed")
                || lower.contains("userauth fail")
                || lower.contains("auth cancel")
                || lower.contains("password");
    }

    private static boolean isTcpPortOpen(String host, int port, int timeoutMs) {
        if (host == null || host.trim().isEmpty()) return false;
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host.trim(), port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public SSH_Multi(String server, String userServer, String pwServer,
                     String loopback, String userCLLS, String pwCLLS,
                     String cmdSet, String device, int rowNum,
                     String userL2, String pwL2) {

        String startTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        logwork("[START] " + loopback + " (" + device + ", " + cmdSet + ") at " + startTime + "\n");

        try {            // 1) (ย้ายไปโหลดหลัง detect vendor/cmdSet เพื่อให้เลือก sheet ถูก)

            // 2) เลือก user/pass (ตามลำดับ L2 > CLLS > Server)
            String sshUser = firstNonEmpty(userL2, userCLLS, userServer);
            String sshPass = firstNonEmpty(pwL2, pwCLLS, pwServer);

            if (sshUser == null || sshPass == null) {
                logwork("[ERROR] SSH credentials are empty for " + loopback + "\n");
                return;
            }

            if (loopback == null || loopback.trim().isEmpty()) {
                logwork("[ERROR] Loopback IP is empty\n");
                return;
            }

            // 3) ต่อ SSH
            boolean connectedOk = connectSSHWithRetry(loopback.trim(), sshUser.trim(), sshPass, rowNum, device, cmdSet);
            if (!connectedOk) { return; }

            // 3.1) เปิด shell เพียงครั้งเดียว แล้ว detect vendor จาก prompt
            //     - ถ้า prompt ลงท้ายด้วย '#' => ZTE
            //     - ถ้า prompt ลงท้ายด้วย '>' => HW
            //     (กันปัญหา session is down ที่เกิดจากการเปิด shell 2 รอบ)
            RunResult rr = runCommandsInShellAutoVendor(cmdSet, device, loopback);
            String cmdSetUsed = rr.cmdSetUsed;
            String allOutput = rr.output;


            // 5) เขียนไฟล์ต่อโหนด
            writeNodeLogFile(rowNum, loopback, device, cmdSetUsed, allOutput);

            String endTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            logwork("[DONE] " + loopback + " (" + device + ", " + cmdSetUsed + ") at " + endTime + "\n");

        } catch (Exception e) {
            logwork("[ERROR] SSH_Multi exception for " + loopback + " : " + e + "\n");
        } finally {
            disconnect();
        }
    }

    // ========= SSH (JSch ใหม่ + BouncyCastle) ==========


    private boolean connectSSHWithRetry(String host, String user, String pass, int rowNum, String device, String cmdSetName) {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                logwork("[DEBUG] Try SSH " + host + " user=" + user
                        + " auth=password,keyboard-interactive,publickey (attempt " + attempt + "/" + MAX_RETRY + ")\n");
                connectSSH(host, SSH_PORT, user, pass);
                return true;
            } catch (Exception e) {
                last = e;

                String err = buildErrorContent(host, device, cmdSetName, attempt, "connectSSH", e);
                logwork("[ERROR] SSH_Multi connect failed for " + host + " attempt " + attempt + "/" + MAX_RETRY
                        + " : " + e + "\n");

                // write per-node log on failure so you can review the reason later
                writeNodeLogFile(rowNum, host, device, cmdSetName, err);

                if (attempt < MAX_RETRY) {
                    try {
                        Thread.sleep((long) RETRY_BACKOFF_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        // already wrote node log; stop this node
        return false;
    }

private void connectSSH(String host, int port, String user, String pass) throws JSchException {
        try {
            // Quick TCP pre-check so we can fail fast with a clearer reason than generic JSch timeout
            if (!isTcpPortOpen(host, port, TCP_PRECHECK_TIMEOUT_MS)) {
                throw new IOException("TCP port " + port + " unreachable (timeout/refused) for " + host);
            }
        } catch (IOException ioe) {
            throw new JSchException(ioe.getMessage(), ioe);
        }


    JSch jsch = new JSch();

    // ===== บังคับใช้ config ที่มี kex เก่า/ใหม่ ตาม static block ด้านบน =====
    // (จริง ๆ static block ก็ set แล้ว แต่ตรงนี้กันไว้ให้แน่ใจอีกทีว่า instance นี้ใช้แน่นอน)
    java.util.Hashtable<String, String> baseCfg = new java.util.Hashtable<>(JSch.getConfig());
    jsch.setConfig(baseCfg);
    // ========================================================

    session = jsch.getSession(user, host, port);
    session.setPassword(pass);

    Properties config = new Properties();
    config.put("StrictHostKeyChecking", "no"); // ไม่เช็ค host key

    // ให้ลอง auth แบบ password ก่อน (โหนดส่วนใหญ่ใช้แบบนี้)
    config.put("PreferredAuthentications", "password,keyboard-interactive,publickey");

    session.setConfig(config);

    logwork("[DEBUG] Try SSH " + host + " user=" + user +
            " auth=" + session.getConfig("PreferredAuthentications") + "\n");

    session.connect(CONNECT_TIMEOUT_MS);
    session.setTimeout(SESSION_SOCKET_TIMEOUT_MS);
    logwork("[INFO] SSH connected to " + host + " as " + user + "\n");
}


    /**
     * รันคำสั่งทั้งหมดใน shell interactive session เดียว
     * ทำงานคล้าย Telnet: login → ได้ prompt → ส่ง cmd1 → รอ prompt → cmd2 → ...
     */

    /**
     * เปลี่ยน prefix cmdSet เป็น vendor + suffix เดิม เช่น
     * N-PTP -> HW-PTP หรือ ZTE-PTP
     */
    private String applyVendorToCmdSet(String beforeCmdSet, String vendor) {
        if (beforeCmdSet == null || beforeCmdSet.trim().isEmpty()) return beforeCmdSet;
        if (vendor == null || vendor.trim().isEmpty()) return beforeCmdSet;

        int idx = beforeCmdSet.indexOf("-");
        String suffix = (idx >= 0) ? beforeCmdSet.substring(idx) : ("-" + beforeCmdSet.trim());
        return vendor.trim().toUpperCase(Locale.ROOT) + suffix;
    }

    /**
     * หา vendor จาก "prompt ตัวแรก" ใน shell:
     * - '#' => ZTE
     * - '>' => HW
     *
     * หมายเหตุ: เปิด ChannelShell ชั่วคราวเพื่ออ่าน banner/prompt แล้วปิดทันที
     */
    private String detectVendorFromPrompt(Session sess) {
        if (sess == null || !sess.isConnected()) return null;

        ChannelShell ch = null;
        try {
            ch = (ChannelShell) sess.openChannel("shell");
            ch.setPty(true);

            InputStream in = ch.getInputStream();
            OutputStream out = ch.getOutputStream();

            ch.connect(CHANNEL_TIMEOUT_MS);

            // กระตุ้นให้ prompt โผล่
            out.write("\n".getBytes("UTF-8"));
            out.flush();

            StringBuilder all = new StringBuilder();
            StringBuilder window = new StringBuilder();
            byte[] buf = new byte[4096];

            long start = System.currentTimeMillis();
            long lastData = start;

            // อ่านสั้น ๆ (ไม่เกิน 2500ms) เพื่อไม่ให้กระทบ logic อื่น
            while (System.currentTimeMillis() - start < 2500) {
                boolean got = readAvailable(in, all, window, buf);
                if (got) {
                    lastData = System.currentTimeMillis();

                    // พยายาม detect จากข้อมูลที่ได้ล่าสุด
                    String v = detectVendorByFirstPromptChar(window.toString());
                    if (v != null) return v;
                }

                // ถ้าไม่มีข้อมูลใหม่มาสักพัก ให้หยุดเร็ว
                if (System.currentTimeMillis() - lastData > 300) break;

                try { Thread.sleep(60); } catch (InterruptedException ignored) { }
            }

            // scan ทั้งหมดอีกรอบ
            return detectVendorByFirstPromptChar(all.toString());

        } catch (Exception ignore) {
            return null;
        } finally {
            try { if (ch != null) ch.disconnect(); } catch (Exception ignore) { }
        }
    }

    /**
     * คืนค่า "ZTE" ถ้าเจอ prompt ลงท้ายด้วย '#', "HW" ถ้า prompt ลงท้ายด้วย '>'
     * ใช้การดู "ท้ายบรรทัดที่ไม่ว่าง" เพื่อเลี่ยง false positive จาก banner
     */
    private String detectVendorByFirstPromptChar(String text) {
        if (text == null || text.isEmpty()) return null;

        String[] ls = text.split("\r?\n");
        for (int i = ls.length - 1; i >= 0; i--) {
            String vendor = detectVendorByPromptLine(ls[i]);
            if (vendor != null) {
                return vendor;
            }
        }
        return null;
    }

    private String detectVendorByPromptLine(String line) {
        String prompt = sanitizePromptLine(line);
        if (prompt.isEmpty()) return null;

        if (prompt.matches("^[A-Za-z0-9._:-]+#$")) {
            return "ZTE";
        }

        if (prompt.matches("^[A-Za-z0-9._:-]+>$")
                || prompt.matches("^<[A-Za-z0-9._:-]+>$")
                || prompt.matches("^\\[(?:~|\\*)?[A-Za-z0-9._:-]+(?:-[^\\]]+)?\\]$")) {
            return "HW";
        }

        return null;
    }

    
    // ====== Result container for one node run ======
    private static class RunResult {
        final String vendor;
        final String cmdSetUsed;
        final String output;

        RunResult(String vendor, String cmdSetUsed, String output) {
            this.vendor = vendor;
            this.cmdSetUsed = cmdSetUsed;
            this.output = output;
        }
    }

    /**
     * เปิด ChannelShell "ครั้งเดียว" เพื่อ:
     * 1) อ่าน prompt แรก แล้วตัดสิน vendor (# => ZTE, > => HW)
     * 2) เลือก cmdSet ที่ถูกต้อง + โหลดคำสั่งจาก Excel (มี fallback)
     * 3) รันคำสั่งทั้งหมดใน channel เดิม
     *
     * เหตุผล: บางโหนดปิดทั้ง session เมื่อเราปิด shell channel แรก (เลยเกิด "session is down" ถ้าเปิด shell รอบสอง)
     */
    private RunResult runCommandsInShellAutoVendor(String baseCmdSet, String promptHint, String host)
            throws JSchException, IOException, InvalidFormatException {

        if (session == null || !session.isConnected()) {
            throw new JSchException("session is down");
        }

        ChannelShell channel = null;
        try {
            channel = (ChannelShell) session.openChannel("shell");
            channel.setPty(true);

            InputStream in = channel.getInputStream();
            OutputStream out = channel.getOutputStream();

            channel.connect(CHANNEL_TIMEOUT_MS);

            StringBuilder all = new StringBuilder();
            StringBuilder window = new StringBuilder();
            byte[] buf = new byte[8192];

            // --- อ่าน banner/prompt แรก ---
            // กระตุ้นให้ prompt โผล่ (อย่าส่งหลายครั้ง)
            try {
                out.write("\n".getBytes("UTF-8"));
                out.flush();
            } catch (Exception ignore) { }

            long start = System.currentTimeMillis();
            long lastData = start;

            while (System.currentTimeMillis() - start < FIRST_PROMPT_TIMEOUT_MS) {
                boolean got = readAvailable(in, all, window, buf);
                if (got) lastData = System.currentTimeMillis();

                // ถ้ามี prompt แล้วหยุดอ่าน
                if (isPrompt(window.toString(), promptHint)) break;

                // ถ้าไม่มีข้อมูลใหม่มาสักพัก ให้หยุดเร็ว
                if (System.currentTimeMillis() - lastData > FIRST_PROMPT_IDLE_BREAK_MS) break;

                try { Thread.sleep(80); } catch (InterruptedException ignored) { }
            }

            // --- detect vendor จาก prompt แรก ---
            String vendor = detectVendorByFirstPromptChar(window.toString());
            if (vendor == null) vendor = detectVendorByFirstPromptChar(all.toString());

            String cmdSetUsed = baseCmdSet;
            if (vendor != null && !vendor.trim().isEmpty()) {
                cmdSetUsed = applyVendorToCmdSet(baseCmdSet, vendor);
            }

            // --- โหลด commands ด้วย fallback ---
            List<String> commands = loadCommandsFromExcel(cmdSetUsed);

            if (commands.isEmpty()) {
                // fallback 1: ใช้ cmdSet เดิม
                cmdSetUsed = baseCmdSet;
                commands = loadCommandsFromExcel(cmdSetUsed);
            }
            if (commands.isEmpty() && vendor != null) {
                // fallback 2: สลับ vendor อีกฝั่ง
                String otherVendor = "ZTE".equalsIgnoreCase(vendor) ? "HW" : "ZTE";
                cmdSetUsed = applyVendorToCmdSet(baseCmdSet, otherVendor);
                commands = loadCommandsFromExcel(cmdSetUsed);
                vendor = otherVendor; // อัปเดตให้สอดคล้องกับ cmdSet ที่ใช้จริง
            }

            logwork("[AUTO-VENDOR] " + host + " vendor=" + vendor + " baseCmdSet=" + baseCmdSet + " -> cmdSetUsed=" + cmdSetUsed
                    + " (cmdCount=" + commands.size() + ")\n");

            if (commands.isEmpty()) {
                all.append("\n[ERROR] No commands found for cmdSet after vendor detect. base=")
                        .append(baseCmdSet).append(", vendor=").append(vendor).append("\n");
                return new RunResult(vendor, cmdSetUsed, all.toString());
            }

            // --- รันคำสั่งทั้งหมดใน channel เดิม ---
            for (String cmd : commands) {
                if (cmd == null || cmd.trim().isEmpty()) continue;
                logwork("[SEND CMD] " + host + " (" + cmdSetUsed + ") -> " + cmd + "\n");

                String send = cmd + "\n";
                out.write(send.getBytes("UTF-8"));
                out.flush();

                window.setLength(0);
                long last = System.currentTimeMillis();

                while (true) {
                    boolean got = readAvailable(in, all, window, buf);
                    if (got) last = System.currentTimeMillis();

                    if (isPrompt(window.toString(), promptHint)) {
                        break;
                    }

                    long now = System.currentTimeMillis();
                    if (now - last > COMMAND_PROMPT_TIMEOUT_MS) {
                        all.append("\n[WARN] timeout waiting prompt for CMD: ").append(cmd).append("\n");
                        break;
                    }
                    if (channel.isClosed()) {
                        all.append("\n[INFO] Channel closed while waiting for CMD: ").append(cmd).append("\n");
                        break;
                    }

                    try { Thread.sleep(100); } catch (InterruptedException ignored) { }
                }

            }

            return new RunResult(vendor, cmdSetUsed, all.toString());

        } finally {
            try { if (channel != null) channel.disconnect(); } catch (Exception ignore) { }
        }
    }


private String runCommandsInShell(List<String> commands, String promptHint)
            throws JSchException, IOException {

        ChannelShell channel = (ChannelShell) session.openChannel("shell");
        channel.setPty(true); // จำลอง terminal จริง (สำคัญกับ Huawei / Cisco)

        InputStream in = channel.getInputStream();
        OutputStream out = channel.getOutputStream();

        channel.connect(CHANNEL_TIMEOUT_MS);

        StringBuilder all = new StringBuilder();
        StringBuilder window = new StringBuilder();
        byte[] buf = new byte[8192];

        // 1) อ่าน banner / prompt แรก
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 3000 && in.available() == 0) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) { }
        }
        readAvailable(in, all, window, buf);

        // 2) ยิงคำสั่งทีละคำสั่ง
        for (String cmd : commands) {
            if (cmd == null || cmd.trim().isEmpty()) continue;

            String send = cmd + "\n";
            out.write(send.getBytes("UTF-8"));
            out.flush();

          //  all.append("\n\n>>>> CMD: ").append(cmd).append("\n");

            window.setLength(0);
            long lastData = System.currentTimeMillis();

            while (true) {
                boolean gotData = readAvailable(in, all, window, buf);

                if (gotData) {
                    lastData = System.currentTimeMillis();
                }

                String winStr = window.toString();
                if (isPrompt(winStr, promptHint)) {
                    break;
                }

                long now = System.currentTimeMillis();
                if (now - lastData > COMMAND_PROMPT_TIMEOUT_MS) {
                    all.append("\n[WARN] timeout waiting prompt for CMD: ").append(cmd).append("\n");
                    break;
                }

                if (channel.isClosed()) {
                    all.append("\n[INFO] Channel closed while waiting for CMD: ").append(cmd).append("\n");
                    break;
                }

                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) { }
            }
        }

        channel.disconnect();
        return all.toString();
    }

    /**
     * อ่านข้อมูลที่มีใน InputStream ทั้งหมด ณ ขณะนั้น
     * คืนค่า true ถ้ามี data เข้ามา
     */
    private boolean readAvailable(InputStream in,
                                  StringBuilder all,
                                  StringBuilder window,
                                  byte[] buf) throws IOException {
        boolean gotData = false;

        while (in.available() > 0) {
            int len = in.read(buf);
            if (len < 0) break;
            String s = new String(buf, 0, len, "UTF-8");
            all.append(s);
            window.append(s);
            if (window.length() > PROMPT_WINDOW_CHARS) {
                window.delete(0, window.length() - PROMPT_WINDOW_CHARS);
            }
            gotData = true;
        }

        return gotData;
    }

    /**
     * ตรวจว่าใน window ล่าสุดมี prompt หรือยัง
     * รองรับทั้งรูปแบบ:
     *  - ZTE/Cisco: HOST# / HOST>
     *  - Huawei user-view: <HOST>
     *  - Huawei config-view: [HOST], [~HOST], [*HOST], [HOST-mode]
     */
    private boolean isPrompt(String window, String promptHint) {
        if (window == null || window.isEmpty()) return false;

        String tail = (window.length() > PROMPT_WINDOW_CHARS)
                ? window.substring(window.length() - PROMPT_WINDOW_CHARS)
                : window;

        String[] lines = tail.split("\r?\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = sanitizePromptLine(lines[i]);
            if (line.isEmpty()) continue;
            return matchesPromptLine(line, promptHint);
        }

        return false;
    }

    private String sanitizePromptLine(String line) {
        if (line == null) return "";
        return line
                .replaceAll("\\u001B\\[[;\\d]*[ -/]*[@-~]", "")
                .replace("\\r", "")
                .trim();
    }

    private boolean matchesPromptLine(String line, String promptHint) {
        String hint = (promptHint != null) ? promptHint.trim() : "";

        if (!hint.isEmpty()) {
            if (line.equals(hint + "#") || line.equals(hint + ">") || line.equals("<" + hint + ">")) {
                return true;
            }

            String quotedHint = java.util.regex.Pattern.quote(hint);
            if (line.matches("^\\[(?:~|\\*)?" + quotedHint + "(?:-[^\\]]+)?\\]$")) {
                return true;
            }

            return false;
        }

        if (line.matches("^[A-Za-z0-9._:-]+[>#]$")) return true;
        if (line.matches("^<[A-Za-z0-9._:-]+>$")) return true;
        return line.matches("^\\[(?:~|\\*)?[A-Za-z0-9._:-]+(?:-[^\\]]+)?\\]$");
    }

    public void disconnect() {
        try {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
            logwork("[INFO] Disconnected SSH session\n");
        } catch (Exception e) {
            logwork("[WARN] Disconnect error: " + e + "\n");
        }
    }

    // ========= อ่าน cmdSet จาก Excel =========

    private List<String> loadCommandsFromExcel(String cmdSetName) throws IOException, InvalidFormatException {
        ensureCmdSetCacheLoaded();

        String key = normalizeCmdSetKey(cmdSetName);
        List<String> cached = CMDSET_CACHE.get(key);
        if (cached == null) {
            logwork("[ERROR] cmdSet column not found: " + cmdSetName + "\n");
            return new ArrayList<>();
        }

        return new ArrayList<>(cached);
    }

    private String normalizeCmdSetKey(String cmdSetName) {
        return (cmdSetName == null) ? "" : cmdSetName.trim().toLowerCase(Locale.ROOT);
    }

    private void ensureCmdSetCacheLoaded() throws IOException, InvalidFormatException {
        if (CMDSET_CACHE_READY) return;

        synchronized (CMDSET_CACHE_LOCK) {
            if (CMDSET_CACHE_READY) return;

            File excelSrc = new File(fileInput.getUserInterface_Input());
            if (!excelSrc.exists()) {
                throw new FileNotFoundException("Excel file not found: " + excelSrc.getAbsolutePath());
            }

            Exception last = null;
            for (int attempt = 1; attempt <= EXCEL_CACHE_OPEN_MAX_RETRY; attempt++) {
                File excelTemp = new File(
                        System.getProperty("java.io.tmpdir"),
                        "cmdsetCache_" + System.currentTimeMillis() + "_" + attempt + ".xlsx"
                );

                try {
                    Files.copy(excelSrc.toPath(), excelTemp.toPath(), StandardCopyOption.REPLACE_EXISTING);

                    try (Workbook workbook = WorkbookFactory.create(excelTemp)) {
                        Sheet sheet = workbook.getSheet(CMDSET_SHEET);
                        if (sheet == null) {
                            throw new IOException("Sheet 'cmdSet' not found");
                        }

                        Row headerRow = sheet.getRow(0);
                        if (headerRow == null) {
                            throw new IOException("cmdSet sheet missing header");
                        }

                        CMDSET_CACHE.clear();
                        DataFormatter formatter = new DataFormatter();
                        int lastCol = headerRow.getLastCellNum();
                        int lastRow = sheet.getLastRowNum();

                        for (int c = 0; c < lastCol; c++) {
                            String header = formatter.formatCellValue(headerRow.getCell(c)).trim();
                            if (header.isEmpty()) continue;

                            List<String> commands = new ArrayList<>();
                            for (int r = 1; r <= lastRow; r++) {
                                Row row = sheet.getRow(r);
                                if (row == null) continue;

                                String cmd = formatter.formatCellValue(row.getCell(c)).trim();
                                if (!cmd.isEmpty()) {
                                    commands.add(cmd);
                                }
                            }

                            CMDSET_CACHE.put(normalizeCmdSetKey(header), Collections.unmodifiableList(commands));
                        }

                        CMDSET_CACHE_READY = true;
                        logwork("[INFO] cmdSet cache loaded: " + CMDSET_CACHE.size() + " columns\n");
                        return;
                    }
                } catch (Exception ex) {
                    last = ex;
                    logwork("[WARN] Cannot open cmdSet cache from Excel, retry "
                            + attempt + "/" + EXCEL_CACHE_OPEN_MAX_RETRY
                            + ": " + ex.getMessage() + "\n");
                    if (attempt >= EXCEL_CACHE_OPEN_MAX_RETRY) {
                        break;
                    }
                    try {
                        Thread.sleep(EXCEL_CACHE_OPEN_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while opening cmdSet cache", ie);
                    }
                } finally {
                    try {
                        Files.deleteIfExists(excelTemp.toPath());
                    } catch (IOException ignore) { }
                }
            }

            if (last instanceof InvalidFormatException) {
                throw (InvalidFormatException) last;
            }
            if (last instanceof IOException) {
                throw (IOException) last;
            }
            if (last != null) {
                throw new IOException("Cannot load cmdSet cache", last);
            }
        }
    }

    // ========= เขียน log per node =========

    private void writeNodeLogFile(int rowNum, String loopback,
                                  String device, String cmdSetName,
                                  String content) {

        try {
            String dateStr = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

            String ip = (loopback != null) ? loopback : "0.0.0.0";
            String devName = (device != null) ? device : "UNKNOWN";
            String jobName = (cmdSetName != null) ? cmdSetName : "UNKNOWN_TASK";

            ip = ip.replaceAll("[\\\\/:*?\"<>|]", "_");
            devName = devName.replaceAll("[\\\\/:*?\"<>|]", "_");
            jobName = jobName.replaceAll("[\\\\/:*?\"<>|]", "_");

            String logFolder = fileInput.getLog();
            Files.createDirectories(Paths.get(logFolder));

            String fileName = "[" + rowNum + "]"
                    + ip + "_"
                    + devName + "_"
                    + jobName + "_"
                    + dateStr + ".txt";

            Path path = Paths.get(logFolder, fileName);

            Files.write(path, content.getBytes("UTF-8"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            logwork("[INFO] Node log saved: " + path.toString() + "\n");

        } catch (IOException e) {
            logwork("[ERROR] Cannot write node log file: " + e + "\n");
        }
    }

    private String buildErrorContent(String loopback, String device, String cmdSet,
                                     int attempt, String phase, Exception e) {

        String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        StringBuilder sb = new StringBuilder();
        sb.append("[").append(ts).append("] ")
          .append("FAILED ").append(phase)
          .append(" attempt ").append(attempt).append("/").append(MAX_RETRY).append("\n");

        sb.append("IP      : ").append(loopback).append("\n");
        sb.append("DEVICE  : ").append(device).append("\n");
        sb.append("TASK    : ").append(cmdSet).append("\n");
        sb.append("ERROR   : ").append(e.getClass().getName()).append(": ").append(e.getMessage()).append("\n\n");

        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        sb.append(sw.toString());

        return sb.toString();
    }


    // ========= logWork =========

    private synchronized void logwork(String logWork) {
        if (logWork != null && !logWork.isEmpty()) {
            synchronized (CONSOLE_LOG_LOCK) {
                System.out.print(logWork);
            }
        }
        try (BufferedWriter log = new BufferedWriter(
                new FileWriter(fileInput.getLogWork() + File.separator + formattedDateTimeLOG + ".txt", true))) {
            log.write(logWork);
            if (!logWork.endsWith("\n")) {
                log.newLine();
            }
        } catch (IOException ex) {
            System.out.println(ex.toString());
        }
    }

    private String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return null;
    }

}
