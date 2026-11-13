package io.ontomato.dataengine.controller;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import java.util.BitSet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.ontomato.dataengine.bean.abcHarness.ABCHarnessCheck;
import io.ontomato.dataengine.bean.abcHarness.ABCHarnessProgramDescription;
import io.ontomato.dataengine.service.BackendSessionEntrance;
import io.ontomato.dataengine.service.CheckService;
import io.ontomato.dataengine.service.CheckServiceV2;
import io.ontomato.dataengine.util.SseEmitterUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
public class CheckController {

	@Autowired
	private CheckService checkService;
	
	@Autowired
	private CheckServiceV2 checkServiceV2;
	
	@PostMapping(path = "/check/queryBySessionId", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter queryBySessionId(@RequestBody JSONObject param) {
		String sseId = UUID.randomUUID().toString();
    	SseEmitter sseEmitter = SseEmitterUtil.createSseEmitter(sseId);

        Executors.newSingleThreadExecutor().execute(() -> {
        	checkService.queryBySessionId(param.getString("sessionId"), sseId, null);
            SseEmitterUtil.closeSseEmitter(sseId);
        });
        return sseEmitter;
	}
	
	@PostMapping(path = "/check/queryBySessionId-v2", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter queryBySessionIdV2(@RequestBody JSONObject param) {
		String sseId = UUID.randomUUID().toString();
    	SseEmitter sseEmitter = SseEmitterUtil.createSseEmitter(sseId);

        String sessionId = param.getString("sessionId");
        AtomicBoolean disconnected = new AtomicBoolean();
        sseEmitter.onCompletion(() -> disconnected.set(true));
        sseEmitter.onTimeout(() -> disconnected.set(true));
        sseEmitter.onError(error -> disconnected.set(true));
        AtomicLong nextHeartbeat = new AtomicLong();
        // One virtual thread per reader, with bounded polling and no leaked executor.
        Thread.startVirtualThread(() -> {
            try {
                ABCHarnessCheck check = awaitCheck(sessionId, () -> {
                    if (disconnected.get() || BackendSessionEntrance.isCancelled(sessionId)) return false;
                    long now = System.nanoTime();
                    if (now >= nextHeartbeat.get()) {
                        nextHeartbeat.set(now + TimeUnit.SECONDS.toNanos(5));
                        if (!SseEmitterUtil.sendHeartbeat(sseId)) return false;
                    }
                    return true;
                }, 60_000, 250, (index, description) -> {
                    JSONObject step = JSONObject.of("label", "program-" + index,
                            "summary", description.getSummary(), "meaning", description.getMeaning());
                    if (!SseEmitterUtil.sendSseEmitter(sseId, JSON.toJSONString(step))) disconnected.set(true);
                });
                if (check == null || disconnected.get()) return;
                if (check.getScore() == null || !Double.isFinite(check.getScore())
                        || check.getScore() < 0 || check.getScore() > 100
                        || check.getConclusion() == null || check.getConclusion().isBlank()
                        || check.getFittedQuestion() == null || check.getFittedQuestion().isBlank()) {
                    throw new IllegalStateException("No valid quality assessment");
                }
                JSONObject result = new JSONObject();
                result.put("conclusion", check.getConclusion());
                result.put("score", check.getScore());
                result.put("fittedQuestion", check.getFittedQuestion());
                SseEmitterUtil.sendSseEmitter(sseId, JSON.toJSONString(result));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Quality check reader failed for session {}", sessionId, e);
                if (!disconnected.get()) {
                    SseEmitterUtil.sendSseEmitter(sseId, JSON.toJSONString(
                        JSONObject.of("error", "Quality check did not complete; no final assessment is available")));
                }
            } finally {
                SseEmitterUtil.closeSseEmitter(sseId);
            }
        });
        return sseEmitter;
    }

    ABCHarnessCheck awaitCheck(String sessionId, BooleanSupplier keepWaiting,
            long timeoutMs, long pollMs) throws InterruptedException, TimeoutException {
        return awaitCheck(sessionId, keepWaiting, timeoutMs, pollMs, (index, description) -> {});
    }

    ABCHarnessCheck awaitCheck(String sessionId, BooleanSupplier keepWaiting,
            long timeoutMs, long pollMs, BiConsumer<Integer, ABCHarnessProgramDescription> onProgress)
            throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        BitSet delivered = new BitSet();
        while (keepWaiting.getAsBoolean()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("Quality check wait expired");
            ABCHarnessCheck check = checkServiceV2.queryBySessionId(sessionId);
            if (check == null) throw new IllegalStateException("Quality check not found");
            if (check.getProgramDescriptions() != null) {
                for (int i = 0; i < check.getProgramDescriptions().size(); i++) {
                    var description = check.getProgramDescriptions().get(i);
                    if (description == null || delivered.get(i)) continue;
                    delivered.set(i);
                    onProgress.accept(i, description);
                    deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
                }
            }
            if (Boolean.TRUE.equals(check.getFinished())) return check;
            TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(pollMs), remaining));
        }
        return null;
    }
}
