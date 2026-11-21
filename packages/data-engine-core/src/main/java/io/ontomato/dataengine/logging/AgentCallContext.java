package io.ontomato.dataengine.logging;

import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

public class AgentCallContext {

    private static final ThreadLocal<AgentCallContext> CONTEXT = new ThreadLocal<>();

    private final String agentRunId;
    private final String sessionId;
    private final String agentName;
    private final String domainId;
    private int round;
    private int consecutiveDslFailures;
    private int pendingDslTrials;
    private final Map<String, Integer> failedPlans = new HashMap<>();
    private final Map<String, Integer> pendingPlans = new HashMap<>();

    private AgentCallContext(String sessionId, String agentName, String domainId) {
        this.agentRunId = UUID.randomUUID().toString();
        this.sessionId = sessionId;
        this.agentName = agentName;
        this.domainId = domainId;
    }

    public static void begin(String sessionId, String agentName, String domainId) {
        CONTEXT.set(new AgentCallContext(sessionId, agentName, domainId));
    }

    public static void end() {
        CONTEXT.remove();
    }

    public static AgentCallContext current() {
        return CONTEXT.get();
    }

    public int nextRound() {
        return ++round;
    }

    public synchronized boolean reserveDslTrial(int limit) {
        return reserveDslTrial(limit, "");
    }

    public synchronized boolean reserveDslTrial(int limit, String plan) {
        if (limit <= 0 || failedPlans.getOrDefault(plan, 0) + pendingPlans.getOrDefault(plan, 0) >= limit) return false;
        pendingPlans.merge(plan, 1, Integer::sum);
        pendingDslTrials++;
        return true;
    }

    public synchronized int finishDslTrial(boolean success) {
        return finishDslTrial(success, "");
    }

    public synchronized int finishDslTrial(boolean success, String plan) {
        if (pendingDslTrials > 0) pendingDslTrials--;
        pendingPlans.computeIfPresent(plan, (key, count) -> count <= 1 ? null : count - 1);
        if (success) {
            failedPlans.clear();
            consecutiveDslFailures = 0;
        } else {
            consecutiveDslFailures = failedPlans.merge(plan, 1, Integer::sum);
        }
        return consecutiveDslFailures;
    }

    public synchronized int getConsecutiveDslFailures() {
        return consecutiveDslFailures;
    }

    public synchronized int getPendingDslTrials() {
        return pendingDslTrials;
    }

    public String getAgentRunId() {
        return agentRunId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getAgentName() {
        return agentName;
    }
    
    public String getDomainId() {
        return domainId;
    }
}
