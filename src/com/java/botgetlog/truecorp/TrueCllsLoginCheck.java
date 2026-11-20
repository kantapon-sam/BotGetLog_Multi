package com.java.botgetlog.truecorp;

import com.truelinkoptical.shared.SharedConnectionBudget;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

/** Login-only worker for the Control Console. Exit codes are its entire response. */
public final class TrueCllsLoginCheck {
    enum Status {
        VERIFIED(0), REJECTED(10), UNCONFIRMED(11), GATEWAY_UNAVAILABLE(12),
        BUSY(13), CONFIG_UNAVAILABLE(14), EVENT_UNSAVED(15);
        final int code;
        Status(int code) { this.code = code; }
    }

    public static void main(String[] args) {
        System.exit(run(args).code);
    }

    static Status run(String[] args) {
        if (args.length != 4 || !args[2].matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")
                || args[3].trim().isEmpty() || args[3].length() > 255 || args[3].chars().anyMatch(Character::isISOControl))
            return Status.CONFIG_UNAVAILABLE;
        String ip = args[2], node = args[3].trim();
        for (String part : ip.split("\\.")) if (Integer.parseInt(part) > 255) return Status.CONFIG_UNAVAILABLE;
        try {
            File workbook = new File(args[0]);
            BotGetLog_TrueCorp.LiveProbeConfig config = BotGetLog_TrueCorp.loadLiveProbeConfig(workbook,
                    TrueCllsCredentialStore.readPrimaryForLoginCheck(workbook));
            if (!config.isComplete() || java.util.stream.Stream.of(config.gatewayUsername, config.gatewayPassword,
                    config.nodeUsername, config.nodePassword).anyMatch(s -> s.chars().anyMatch(Character::isISOControl)))
                return Status.CONFIG_UNAVAILABLE;
            SharedConnectionBudget budget = SharedConnectionBudget.fromEnvironment();
            try (SharedConnectionBudget.LiveRequest ticket = budget.enqueueLive("Control CLLS test", 1)) {
                if (!ticket.tryActivate()) return Status.BUSY;
                try (SharedConnectionBudget.Lease lease = budget.tryAcquireLive(ticket.id)) {
                    if (lease == null) return Status.BUSY;
                    for (String gateway : config.gatewayServers) {
                        Status result;
                        try (Telnet_Multi.CredentialProbe probe = new Telnet_Multi.CredentialProbe()) {
                            result = probe.checkPrimary(gateway, config, ip, node);
                        }
                        if (result == Status.GATEWAY_UNAVAILABLE) continue;
                        if (result == Status.VERIFIED) {
                            String event = CllsFallback.cllsSuccessEvent(ip, node, config.nodeUsername, 0);
                            if (event.isEmpty()) return Status.EVENT_UNSAVED;
                            try { CllsFallback.appendEvent(Paths.get(args[1]), event); }
                            catch (IOException unavailable) { return Status.EVENT_UNSAVED; }
                        }
                        return result;
                    }
                    return Status.GATEWAY_UNAVAILABLE;
                }
            }
        } catch (IOException | RuntimeException unavailable) {
            return Status.CONFIG_UNAVAILABLE;
        }
    }
}
