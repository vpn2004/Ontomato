package io.ontomato.dataengine.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AgentCallContextTest {
    @AfterEach void clear() { AgentCallContext.end(); }

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
