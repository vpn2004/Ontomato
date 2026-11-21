import type {
  AgentResponseEvent,
  AgentResponseTerminalStatus,
} from "@ontomato/contracts/agent-chat";

type TerminalEvent = Extract<AgentResponseEvent, { type: "response_end" | "error" }>;

export interface BufferedResponseEvent<E> {
  seq: number;
  event: E;
  terminal: boolean;
}

export type ResponseEventSubscriber<E> = (event: BufferedResponseEvent<E>) => boolean | void;

function isTerminal(event: { type: string }): event is TerminalEvent {
  return event.type === "response_end" || event.type === "error";
}

/**
 * The transient event tail for one server-owned response.
 *
 * It intentionally has no independent business id or persistence. The owning
 * session retains it for replay; `Extra` lets an owner add its own non-terminal events.
 */
export class SessionResponseStream<Extra extends { type: string } = never> {
  readonly controller = new AbortController();
  private readonly events: BufferedResponseEvent<AgentResponseEvent | Extra>[] = [];
  private readonly subscribers = new Set<ResponseEventSubscriber<AgentResponseEvent | Extra>>();
  private terminalStatus?: AgentResponseTerminalStatus;

  get status(): "running" | AgentResponseTerminalStatus {
    return this.terminalStatus ?? "running";
  }

  append(event: AgentResponseEvent | Extra): BufferedResponseEvent<AgentResponseEvent | Extra> {
    if (this.terminalStatus) {
      throw new Error("Cannot append to a finished response");
    }

    const terminal = isTerminal(event);
    const buffered: BufferedResponseEvent<AgentResponseEvent | Extra> = {
      seq: this.events.length + 1,
      event,
      terminal,
    };
    this.events.push(buffered);

    for (const subscriber of [...this.subscribers]) {
      if (subscriber(buffered) === false) {
        this.subscribers.delete(subscriber);
      }
    }

    if (isTerminal(event)) {
      this.terminalStatus = event.type === "error" ? "failed" : event.status;
      this.subscribers.clear();
    }

    return buffered;
  }

  subscribe(
    afterSeq: number,
    subscriber: ResponseEventSubscriber<AgentResponseEvent | Extra>
  ): () => void {
    for (const event of this.events) {
      if (event.seq <= afterSeq) continue;
      if (subscriber(event) === false || event.terminal) return () => {};
    }

    if (this.terminalStatus) return () => {};
    this.subscribers.add(subscriber);
    return () => {
      this.subscribers.delete(subscriber);
    };
  }

  cancel(): boolean {
    if (this.terminalStatus || this.controller.signal.aborted) return false;
    this.controller.abort();
    return true;
  }
}
