package com.java.botgetlog.truecorp;

import com.truelinkoptical.shared.SharedConnectionBudget;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Local-only slot admission regression: no gateway or credential file is used. */
public final class SharedBudgetBotIntegrationRegression {
    public static void main(String[] args) throws Exception {
        verifyOpaqueTicketParsing();
        verifyEmptyTicketFailsBeforeProbeSetup();
        verifyBotWaitsForAReleasedSlot();
        verifyWaitingBotStopsWithoutPreemption();
        verifyStopAfterAcquireReturnsTheSlot();
        verifyProbeCannotBypassTheSharedBudget();
        System.out.println("PASS SharedBudgetBotIntegrationRegression");
    }

    private static SharedConnectionBudget budget() throws IOException {
        return new SharedConnectionBudget(Files.createTempDirectory("bot-budget-integration-"), 2, 1);
    }

    private static void verifyOpaqueTicketParsing() {
        Map<String,String> equals = TrueLiveNodeProbeCli.parseArgs(new String[]{
            "--BUDGET-TICKET=AbC-123_XyZ", "--node=CPE-TEST", "--cmdset=HW-LLDP-Link_OPTIC"
        });
        check("AbC-123_XyZ".equals(equals.get("budget-ticket")), "Ticket case must be preserved.");
        check("CPE-TEST".equals(equals.get("node")), "Argument values must retain their case.");
        check("HW-LLDP-Link_OPTIC".equals(equals.get("cmdset")), "Command set case must be preserved.");
        Map<String,String> separated = TrueLiveNodeProbeCli.parseArgs(new String[]{"--budget-ticket", "AbC-123_XyZ"});
        check(equals.get("budget-ticket").equals(separated.get("budget-ticket")), "Both ticket syntaxes must agree.");
    }

    private static void verifyEmptyTicketFailsBeforeProbeSetup() throws Exception {
        PrintStream previous = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        int code;
        try (PrintStream output = new PrintStream(captured, true, "UTF-8")) {
            System.setOut(output);
            code = TrueLiveNodeProbeCli.run(new String[]{"--ip=192.0.2.1", "--node=CPE-TEST",
                "--cmdset=N-LLDP-Link_OPTIC", "--budget-ticket="});
        } finally {
            System.setOut(previous);
        }
        check(code == 2 && captured.toString("UTF-8").contains("INVALID_REQUEST"),
                "An explicit empty ticket must fail before credentials or network setup.");
    }

    private static void verifyBotWaitsForAReleasedSlot() throws Exception {
        SharedConnectionBudget budget = budget();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (SharedConnectionBudget.Registration registration = budget.registerBot();
                SharedConnectionBudget.Lease first = budget.tryAcquireBot();
                SharedConnectionBudget.Lease second = budget.tryAcquireBot()) {
            check(first != null && second != null, "Test must fill both physical slots.");
            CountDownLatch entered = new CountDownLatch(1);
            Future<SharedConnectionBudget.Lease> waiting = worker.submit(() -> {
                entered.countDown();
                return BotGetLog_TrueCorp.awaitSharedBotLease(budget, () -> false);
            });
            check(entered.await(1,TimeUnit.SECONDS), "Waiting worker must start.");
            Thread.sleep(100L);
            check(!waiting.isDone(), "Bot must wait while all physical slots are occupied.");
            first.close();
            try (SharedConnectionBudget.Lease acquired = waiting.get(2,TimeUnit.SECONDS)) {
                check(acquired != null, "Bot must resume after a slot is released.");
                check(budget.tryAcquireBot() == null, "The resumed task must own its physical slot.");
            }
        } finally {
            worker.shutdownNow();
            worker.awaitTermination(2,TimeUnit.SECONDS);
        }
    }

    private static void verifyWaitingBotStopsWithoutPreemption() throws Exception {
        SharedConnectionBudget budget = budget();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicBoolean stopped = new AtomicBoolean(false);
        try (SharedConnectionBudget.Registration registration = budget.registerBot();
                SharedConnectionBudget.Lease first = budget.tryAcquireBot();
                SharedConnectionBudget.Lease second = budget.tryAcquireBot()) {
            Future<Boolean> waiting = worker.submit(() -> {
                try (SharedConnectionBudget.Lease ignored = BotGetLog_TrueCorp.awaitSharedBotLease(budget, stopped::get)) {
                    return false;
                } catch (InterruptedException expected) {
                    return true;
                }
            });
            Thread.sleep(100L);
            stopped.set(true);
            check(waiting.get(2,TimeUnit.SECONDS), "A stopped waiting task must not open a connection.");
            check(budget.tryAcquireBot() == null, "Stopping a waiter must not release existing connections.");
        } finally {
            worker.shutdownNow();
            worker.awaitTermination(2,TimeUnit.SECONDS);
        }
    }

    private static void verifyStopAfterAcquireReturnsTheSlot() throws Exception {
        SharedConnectionBudget budget = budget();
        AtomicInteger checks = new AtomicInteger();
        try (SharedConnectionBudget.Registration registration = budget.registerBot()) {
            boolean stopped = false;
            try (SharedConnectionBudget.Lease ignored = BotGetLog_TrueCorp.awaitSharedBotLease(budget,
                    () -> checks.incrementAndGet() > 1)) {
                throw new AssertionError("A stop after admission must prevent connection setup.");
            } catch (InterruptedException expected) {
                stopped = true;
            }
            check(stopped, "Stop race must be reported.");
            try (SharedConnectionBudget.Lease first = budget.tryAcquireBot();
                    SharedConnectionBudget.Lease second = budget.tryAcquireBot()) {
                check(first != null && second != null, "Admission cancelled by stop must return its slot.");
            }
        }
    }

    private static void verifyProbeCannotBypassTheSharedBudget() throws Exception {
        SharedConnectionBudget budget = budget();
        try (SharedConnectionBudget.Registration registration = budget.registerBot();
                SharedConnectionBudget.Lease bot = budget.tryAcquireBot();
                SharedConnectionBudget.LiveRequest request = budget.enqueueLive("test-owner",1)) {
            check(request.tryActivate(), "One live slot must be reservable alongside one Bot slot.");
            try (SharedConnectionBudget.Lease live = TrueLiveNodeProbeCli.acquireProbeLease(budget,true,request.id)) {
                check(live != null, "A valid reserved ticket must acquire one physical live slot.");
                check(TrueLiveNodeProbeCli.acquireProbeLease(budget,false,"") == null,
                        "Standalone CLI must not bypass the full shared budget.");
            }
            boolean rejected = false;
            try (SharedConnectionBudget.Lease invalid = TrueLiveNodeProbeCli.acquireProbeLease(budget,true,"unknown-ticket")) {
                rejected = invalid == null;
            } catch (IOException | IllegalArgumentException expected) {
                rejected = true;
            }
            check(rejected, "An invalid live ticket must never fall back to a Bot lease.");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
