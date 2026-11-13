package io.ontomato.dataengine.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import io.ontomato.dataengine.bean.abcHarness.ABCHarnessCheck;
import io.ontomato.dataengine.bean.abcHarness.ABCHarnessProgramDescription;
import io.ontomato.dataengine.service.CheckServiceV2;

class CheckControllerPollingTest {
    private final CheckServiceV2 service = mock(CheckServiceV2.class);
    private final CheckController controller = new CheckController();

    CheckControllerPollingTest() {
        ReflectionTestUtils.setField(controller, "checkServiceV2", service);
    }

    @Test void waitsBetweenReadsAndReturnsOnlyTheFinishedRecord() throws Exception {
        var times = new ArrayList<Long>();
        var finished = new ABCHarnessCheck();
        finished.setFinished(true);
        when(service.queryBySessionId("session")).thenAnswer(call -> {
            times.add(System.nanoTime());
            return times.size() == 3 ? finished : new ABCHarnessCheck();
        });
        assertSame(finished, controller.awaitCheck("session", () -> true, 2000, 15));
        assertEquals(3, times.size());
        assertTrue(times.get(1) - times.get(0) >= TimeUnit.MILLISECONDS.toNanos(10));
        assertTrue(times.get(2) - times.get(1) >= TimeUnit.MILLISECONDS.toNanos(10));
    }

    @Test void disconnectStopsFurtherDatabaseReads() throws Exception {
        when(service.queryBySessionId("session")).thenReturn(new ABCHarnessCheck());
        var polls = new AtomicInteger();
        assertNull(controller.awaitCheck("session", () -> polls.incrementAndGet() <= 2, 2000, 1));
        verify(service, times(2)).queryBySessionId("session");
    }

    @Test void timesOutWithoutReturningAnUnfinishedAssessment() {
        when(service.queryBySessionId("session")).thenReturn(new ABCHarnessCheck());
        assertThrows(TimeoutException.class, () -> controller.awaitCheck("session", () -> true, 35, 10));
        verify(service, atMost(4)).queryBySessionId("session");
    }

    @Test void missingTaskIsAnErrorRatherThanAnEmptySuccess() {
        assertThrows(IllegalStateException.class, () -> controller.awaitCheck("missing", () -> true, 100, 10));
    }

    @Test void newProgramEvidenceRenewsTheIdleDeadlineUntilTheFinalAssessment() throws Exception {
        var count = new AtomicInteger();
        var descriptions = new ArrayList<ABCHarnessProgramDescription>();
        when(service.queryBySessionId("long-review")).thenAnswer(call -> {
            var description = new ABCHarnessProgramDescription();
            description.setSummary("Program " + count.incrementAndGet());
            description.setMeaning("Verified query semantics " + count.get());
            descriptions.add(description);
            var check = new ABCHarnessCheck();
            check.setProgramDescriptions(List.copyOf(descriptions));
            check.setFinished(count.get() == 5);
            return check;
        });
        assertTrue(controller.awaitCheck("long-review", () -> true, 180, 70).getFinished());
        assertEquals(5, count.get());
    }

    @Test void unchangedPartialEvidenceDoesNotRenewTheDeadlineOrRepeatDelivery() {
        var description = new ABCHarnessProgramDescription(); description.setMeaning("Evidence");
        var partial = new ABCHarnessCheck(); partial.setProgramDescriptions(List.of(description));
        when(service.queryBySessionId("partial")).thenReturn(partial);
        var delivered = new AtomicInteger();
        assertThrows(TimeoutException.class, () -> controller.awaitCheck("partial", () -> true, 40, 10,
            (index, item) -> delivered.incrementAndGet()));
        assertEquals(1, delivered.get());
    }
}
