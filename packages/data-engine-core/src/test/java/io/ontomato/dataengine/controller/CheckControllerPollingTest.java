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

    @Test void assessmentPhaseHasItsOwnDeadlineAndIsEmittedOnce() throws Exception {
        var count = new AtomicInteger();
        var phases = new ArrayList<String>();
        when(service.queryBySessionId("assessment")).thenAnswer(call -> {
            var check = new ABCHarnessCheck();
            check.setPhase("assessing");
            check.setTotalPrograms(1);
            check.setFinished(count.incrementAndGet() >= 7);
            return check;
        });
        var result = controller.awaitCheck("assessment", () -> true, 40, 15,
                (i, d) -> {}, check -> phases.add(check.getPhase()), 300, 1000);
        assertTrue(result.getFinished());
        assertEquals(List.of("assessing"), phases);
    }

    @Test void publishesIncreasingDescriptionCountsWithinTheSamePhase() throws Exception {
        var polls = new AtomicInteger();
        var counts = new ArrayList<Long>();
        var description = new ABCHarnessProgramDescription(); description.setSummary("Verified");
        when(service.queryBySessionId("progress")).thenAnswer(call -> {
            int count = Math.min(polls.getAndIncrement(), 2);
            var check = new ABCHarnessCheck(); check.setPhase("describing"); check.setTotalPrograms(2);
            check.setProgramDescriptions(java.util.Arrays.asList(count > 0 ? description : null, count > 1 ? description : null));
            check.setFinished(polls.get() == 4);
            return check;
        });
        controller.awaitCheck("progress", () -> true, 1000, 1, (i, d) -> {},
            check -> counts.add(check.getProgramDescriptions().stream().filter(java.util.Objects::nonNull).count()), 1000, 2000);
        assertEquals(List.of(0L, 1L, 2L), counts);
    }

    @Test void repeatedPhaseFramesDoNotKeepAnAssessmentAlive() {
        var check = new ABCHarnessCheck(); check.setPhase("assessing"); check.setTotalPrograms(1);
        when(service.queryBySessionId("stuck")).thenReturn(check);
        assertThrows(TimeoutException.class, () -> controller.awaitCheck("stuck", () -> true, 20, 10,
                (i, d) -> {}, item -> {}, 60, 1000));
    }
}
