import type { GovernanceWork } from "@ontomato/contracts/knowledge-governance";
import type { AgentEvent, AgentState } from "../../core/agent-loop/types";
import { runAgentLoop } from "../../core/agent-loop/agent-loop";
import { toResponseEvent } from "../../core/agent-loop/chat-view";
import { createHarnessRun } from "../../core/agent-loop/session-run";
import {
  harnessSessions,
  HarnessSessionBusyError,
  type HarnessTurnStatus,
} from "../../infrastructure/harness-sessions";
import { renderPrompt } from "../../core/prompts/loader";
import { ModelNotConfiguredError, resolveModelForRole } from "../../config/model-resolver";
import { runWithLogContext } from "../../logging/log-context";
import { createLogger } from "../../logging/logger";
import { HttpError } from "../../utils/errors";
import { SessionResponseStream } from "../../utils/response-stream";
import { createSseErrorEvent } from "../../utils/sse";
import {
  governanceStore,
  publicGovernanceSession,
  type GovernanceOwner,
  type GovernanceRecord,
} from "./store";
import { createGovernanceTools } from "./tools";
import type { GovernanceCredentials } from "./materials";

const logger = createLogger("knowledge-governance");
const compactionPrompts = {
  system: "knowledge-governance.compaction.system",
  summarize: "knowledge-governance.compaction.summarize.user",
  update: "knowledge-governance.compaction.update.user",
};

/** Saved business work, sent after each save so the page follows progress live. */
type GovernanceWorkEvent = { type: "work"; work: GovernanceWork };
export type GovernanceResponseStream = SessionResponseStream<GovernanceWorkEvent>;

export class GovernanceRuntime {
  // One entry per session, keyed to the Harness turn it streams. Held only while that turn
  // runs; afterwards the settled history is the only source.
  private active = new Map<
    string,
    { turnId: string; stream: GovernanceResponseStream; done?: Promise<void> }
  >();

  async start(
    owner: GovernanceOwner,
    credentials: GovernanceCredentials,
    locale: string,
    message: string
  ) {
    const record = await governanceStore.create(owner);
    return this.respond(record.id, owner, credentials, locale, message);
  }

  async respond(
    id: string,
    owner: GovernanceOwner,
    credentials: GovernanceCredentials,
    locale: string,
    message: string
  ) {
    let record = await governanceStore.get(id, owner);
    let turnId: string;
    try {
      ({ turnId } = await harnessSessions.beginTurn(record.harnessSessionId));
    } catch (error) {
      if (error instanceof HarnessSessionBusyError)
        throw new HttpError(409, "Governance session is already running");
      throw error;
    }
    // Registered in the same tick as admission, so any snapshot that reports this turn
    // running can subscribe to it.
    const stream: GovernanceResponseStream = new SessionResponseStream();
    stream.append({ type: "response_started", message });
    const entry: { turnId: string; stream: GovernanceResponseStream; done?: Promise<void> } = {
      turnId,
      stream,
    };
    this.active.set(id, entry);
    const release = () => {
      if (this.active.get(id) === entry) this.active.delete(id);
    };
    try {
      // Admission may have waited for the prior turn; take its final business work now.
      record = await governanceStore.get(id, owner);
    } catch (error) {
      await harnessSessions.finishTurn(record.harnessSessionId, turnId, "failed");
      stream.append(createSseErrorEvent(error));
      release();
      throw error;
    }
    const done = runWithLogContext(
      { domainId: owner.domainId, token: credentials.token, apiKey: credentials.apiKey },
      () => this.run(record, turnId, credentials, locale, message, stream)
    );
    entry.done = done;
    void done
      .finally(release)
      .catch((error) => {
        logger.error("Governance persistence failed", { sessionId: id, error: String(error) });
      });
    return publicGovernanceSession(await governanceStore.get(id, owner));
  }

  async stop(id: string, owner: GovernanceOwner) {
    await governanceStore.authorize(id, owner);
    this.active.get(id)?.stream.cancel();
    return { success: true };
  }

  /** Replayable events of that Harness turn; undefined once it settled or another turn runs. */
  async responseStream(id: string, owner: GovernanceOwner, turnId: string) {
    await governanceStore.authorize(id, owner);
    const entry = this.active.get(id);
    return entry?.turnId === turnId ? entry.stream : undefined;
  }

  async wait(id: string) {
    await this.active.get(id)?.done;
  }

  private async run(
    record: GovernanceRecord,
    turnId: string,
    credentials: GovernanceCredentials,
    locale: string,
    message: string,
    stream: GovernanceResponseStream
  ) {
    const signal = stream.controller.signal;
    let queue = Promise.resolve();
    const persist = () => {
      queue = queue.then(() => governanceStore.save(record, turnId));
      return queue;
    };
    const persistWork = async () => {
      await persist();
      stream.append({ type: "work", work: structuredClone(record.work) });
    };
    let terminal: Exclude<HarnessTurnStatus, "running">;
    try {
      signal.throwIfAborted();
      record.error = null;
      const model = await resolveModelForRole("knowledgeGovernance");
      const modelConfig = {
        baseUrl: model.baseUrl,
        apiKey: model.apiKey,
        modelName: model.modelName,
        maxTokens: model.maxTokens,
        modelKwargs: model.customRequestParameters,
        llmLog: { agentName: "KnowledgeGovernance", agentRunId: turnId },
      };
      const state: AgentState = {
        systemPrompt: "",
        messages: [],
        tools: createGovernanceTools(record, credentials, persistWork),
        isStreaming: false,
      };
      // Governance conversations do not show model thinking.
      const relay = (event: AgentEvent) => {
        const streamEvent = toResponseEvent(event);
        if (streamEvent && streamEvent.type !== "thinking") stream.append(streamEvent);
      };
      await runAgentLoop(message, state, modelConfig, relay, signal, {
        session: await createHarnessRun(harnessSessions, {
          sessionId: record.harnessSessionId,
          turnId,
          contextWindow: model.contextWindow,
          promptKeys: compactionPrompts,
        }),
        beforeTurn: async (current) => {
          signal.throwIfAborted();
          current.systemPrompt = renderPrompt(
            "knowledge-governance.main.system",
            {
              progress: JSON.stringify({
                summary: record.work.summary,
                notes: record.work.notes,
                reviewed: record.work.reviewedKnowledgeIds.length,
                knowledgeTotal: record.work.knowledgeTotal,
                issues: record.work.issues.map((issue) => ({
                  id: issue.id,
                  title: issue.title,
                  status: issue.status,
                })),
              }),
            },
            locale
          );
        },
      });
      const last = state.messages.at(-1);
      if (last?.role === "assistant" && last.stopReason === "error")
        throw new Error(last.errorMessage || "Model request failed");
      terminal = signal.aborted ? "cancelled" : "completed";
    } catch (error) {
      terminal = signal.aborted ? "cancelled" : "failed";
      if (!signal.aborted) {
        // When no model is configured for the role, directly provide the localized reason; other failures keep the generic message.
        record.error =
          error instanceof ModelNotConfiguredError
            ? error.message
            : "The governance response could not finish. Saved work is retained; please retry.";
        logger.error("Governance response failed", { sessionId: record.id, error: String(error) });
      }
    }
    let failure: unknown = null;
    try {
      await queue;
      await governanceStore.save(record, turnId);
    } catch (error) {
      terminal = "failed";
      failure = error;
    }
    try {
      await harnessSessions.finishTurn(record.harnessSessionId, turnId, terminal);
    } catch (error) {
      terminal = "failed";
      failure ??= error;
    }
    // Sent after the turn is settled, so a subscriber reloading history sees the final state.
    stream.append(
      terminal === "failed"
        ? createSseErrorEvent(record.error ?? failure)
        : { type: "response_end", status: terminal }
    );
    if (failure) throw failure;
  }
}

export const governanceRuntime = new GovernanceRuntime();
