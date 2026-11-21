package io.ontomato.dataengine.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AgentCallContextTest {
    @AfterEach void clear() { AgentCallContext.end(); }

    @Test void concurrentTrialsCannotExceedTheFailureBudget() throws Exception {
        AgentCallContext.begin("concurrent-session", "ABCProgrammer", "domain-a");
        AgentCallContext context = AgentCallContext.current();
        int workers = 16;
        var executor = Executors.newFixedThreadPool(workers);
        var start = new CountDownLatch(1);
        var attempted = new CountDownLatch(workers);
        var finish = new CountDownLatch(1);
        var results = new ArrayList<Future<Boolean>>();
        try {
            for (int i = 0; i < workers; i++) {
                results.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    boolean reserved = context.reserveDslTrial(4);
                    attempted.countDown();
                    assertTrue(finish.await(5, TimeUnit.SECONDS));
                    if (reserved) context.finishDslTrial(false);
                    return reserved;
                }));
            }
            start.countDown();
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertEquals(4, context.getPendingDslTrials());
            assertEquals(0, context.getConsecutiveDslFailures());
            finish.countDown();
            int accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) accepted++;
            }
            assertEquals(4, accepted);
            assertEquals(0, context.getPendingDslTrials());
            assertEquals(4, context.getConsecutiveDslFailures());
            assertFalse(context.reserveDslTrial(4));
        } finally {
            finish.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void consecutiveDslFailuresResetOnlyAfterSuccessAndAreScopedToAgentCall() {
        AgentCallContext.begin("session-a", "ABCProgrammer", "domain-a");
        AgentCallContext first = AgentCallContext.current();
        assertTrue(first.reserveDslTrial(4));
        assertEquals(1, first.finishDslTrial(false));
        assertTrue(first.reserveDslTrial(4));
        assertEquals(2, first.finishDslTrial(false));
        assertTrue(first.reserveDslTrial(4));
        assertEquals(0, first.finishDslTrial(true));
        assertEquals(0, first.getConsecutiveDslFailures());
        assertTrue(first.reserveDslTrial(4));
        assertEquals(1, first.finishDslTrial(false));

        assertTrue(first.reserveDslTrial(4));
        assertTrue(first.reserveDslTrial(4));
        assertTrue(first.reserveDslTrial(4));
        assertFalse(first.reserveDslTrial(4));
        first.finishDslTrial(false);
        first.finishDslTrial(false);
        first.finishDslTrial(false);
        assertEquals(4, first.getConsecutiveDslFailures());
        assertFalse(first.reserveDslTrial(4));

        AgentCallContext.begin("session-b", "ABCProgrammer", "domain-a");
        assertEquals(0, AgentCallContext.current().getConsecutiveDslFailures());
    }
}
