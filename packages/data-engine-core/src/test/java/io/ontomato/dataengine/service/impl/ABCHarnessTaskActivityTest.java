package io.ontomato.dataengine.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import io.ontomato.dataengine.bean.abcHarness.ABCHarnessTaskMessage;
import io.ontomato.dataengine.service.AgentWorkspaceService;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

class ABCHarnessTaskActivityTest {
    ABCHarnessTaskServiceImpl service;
    ExecutorService executor;
    @BeforeEach void start() throws Exception {
        service = new ABCHarnessTaskServiceImpl();
        ReflectionTestUtils.setField(service, "workspaceService", mock(AgentWorkspaceService.class));
        service.initService(); executor = Executors.newCachedThreadPool();
        for (String id : List.of("a", "b")) { service.createTask(id, 1, "user", null, "en"); service.takeMessages(id); }
    }
    @AfterEach void stop() { service.stopService(); executor.shutdownNow(); }
    void push(String id) { var message = new ABCHarnessTaskMessage(); message.setSessionId(id); service.pushMessage(message); }

    @Test void messagesArrivingBeforeWaitAreNotLostAndRemainOrdered() throws Exception {
        push("a"); push("a"); service.getTaskBySessionId("a");
        long start = System.nanoTime(); service.awaitActivity("a", 2000);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 300);
        assertEquals(2, service.takeMessages("a").size());
        assertTrue(service.takeMessages("a").isEmpty());
    }
    @Test void wakesOnlyTheIntendedSessionAndDoesNotLeaveStaleSignals() throws Exception {
        var waiting = new CountDownLatch(1);
        var a = executor.submit(() -> { waiting.countDown(); service.awaitActivity("a", 2000); return true; });
        assertTrue(waiting.await(1, TimeUnit.SECONDS)); push("b"); service.getTaskBySessionId("b");
        assertThrows(TimeoutException.class, () -> a.get(100, TimeUnit.MILLISECONDS));
        push("a"); assertTrue(a.get(500, TimeUnit.MILLISECONDS)); service.takeMessages("a");
        var next = executor.submit(() -> { service.awaitActivity("a", 2000); return true; });
        assertThrows(TimeoutException.class, () -> next.get(100, TimeUnit.MILLISECONDS));
        service.signalActivity("a"); assertTrue(next.get(500, TimeUnit.MILLISECONDS));
    }
    @Test void removalReleasesWaitersAndCleansUpSignals() throws Exception {
        var a = executor.submit(() -> { service.awaitActivity("a", 2000); return true; });
        service.removeTask("a"); assertNull(service.getTaskBySessionId("a"));
        assertTrue(a.get(500, TimeUnit.MILLISECONDS));
        var signals = (Map<?, ?>)ReflectionTestUtils.getField(service, "activity");
        assertFalse(signals.containsKey("a")); assertTrue(signals.containsKey("b"));
    }
    @Test void cancellationInterruptsTheWaitAndDrainPreservesTheInterruptedBatch() throws Exception {
        var interrupted = new CountDownLatch(1);
        var waiting = new CountDownLatch(1);
        var a = executor.submit(() -> {
            waiting.countDown();
            try { service.awaitActivity("a", 5000); }
            catch (InterruptedException e) { interrupted.countDown(); }
        });
        assertTrue(waiting.await(1, TimeUnit.SECONDS)); a.cancel(true);
        assertTrue(interrupted.await(500, TimeUnit.MILLISECONDS));
        push("a"); Thread.currentThread().interrupt();
        try { assertEquals(1, service.takeMessages("a").size()); assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
    }
}
