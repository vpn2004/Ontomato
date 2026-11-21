import type { SseHeartbeatData, SseOpenData } from "@ontomato/contracts/sse";
import type { SseErrorEvent } from "@ontomato/contracts/errors";
import type { AgentResponseEvent } from "@ontomato/contracts/agent-chat";
import type { Request, Response } from "express";
import { toErrorMessage } from "./errors";
import { createLogger } from "../logging/logger";
import type { BufferedResponseEvent, SessionResponseStream } from "./response-stream";

const logger = createLogger("sse");

export function createSseErrorEvent(error: unknown, timestamp: number = Date.now()): SseErrorEvent {
  return {
    type: "error",
    error: toErrorMessage(error),
    timestamp,
  };
}

export function initializeSse(res: Response): void {
  res.setHeader("Content-Type", "text/event-stream");
  res.setHeader("Cache-Control", "no-cache, no-transform");
  res.setHeader("Connection", "keep-alive");
  res.setHeader("X-Accel-Buffering", "no");

  try {
    (res as Response & { flushHeaders?: () => void }).flushHeaders?.();
  } catch {
    // flushHeaders may fail in some environments, ignore
  }

  res.write("event: open\n");
  res.write(`data: ${JSON.stringify({ ok: true } satisfies SseOpenData)}\n\n`);
}

export function writeSseError(res: Response, error: unknown): void {
  res.write(`data: ${JSON.stringify(createSseErrorEvent(error))}\n\n`);
}

function parseLastEventSeq(req: Request): number {
  const seq = Number(req.header("last-event-id"));
  return Number.isFinite(seq) ? Math.max(0, seq) : 0;
}

function isResponseClosed(res: Response): boolean {
  return res.writableEnded || Boolean((res as Response & { destroyed?: boolean }).destroyed);
}

function writeBufferedEvent(res: Response, buffered: BufferedResponseEvent<unknown>): boolean {
  if (isResponseClosed(res)) return false;
  try {
    res.write(`id: ${buffered.seq}\n`);
    res.write(`data: ${JSON.stringify(buffered.event)}\n\n`);
    if (buffered.terminal && !isResponseClosed(res)) res.end();
    return true;
  } catch {
    try {
      res.end();
    } catch {
      /* ignore */
    }
    return false;
  }
}

/** Attach one HTTP subscriber. Closing it never cancels the owning response. */
export function subscribeResponseStream<Extra extends { type: string }>(
  req: Request,
  res: Response,
  stream: SessionResponseStream<Extra>,
  heartbeatMs = 15_000
): void {
  initializeSse(res);
  let closed = false;
  let unsubscribe = () => {};
  const heartbeat = setInterval(() => {
    try {
      if (!isResponseClosed(res)) {
        res.write("event: heartbeat\n");
        res.write(`data: ${JSON.stringify({ ts: Date.now() } satisfies SseHeartbeatData)}\n\n`);
      }
    } catch {
      cleanup();
    }
  }, heartbeatMs);
  heartbeat.unref?.();

  const cleanup = () => {
    if (closed) return;
    closed = true;
    clearInterval(heartbeat);
    unsubscribe();
  };

  res.on("close", cleanup);
  unsubscribe = stream.subscribe(parseLastEventSeq(req), (event) => {
    const written = writeBufferedEvent(res, event);
    if (!written || event.terminal) cleanup();
    return written;
  });

  if (stream.status !== "running") {
    cleanup();
    if (!isResponseClosed(res)) res.end();
  }
}

/** Tell a subscriber that no response tail is held, so it reads the settled history. */
export function writeUnavailableStream(res: Response): void {
  initializeSse(res);
  try {
    res.write(
      `data: ${JSON.stringify({ type: "response_unavailable" } satisfies AgentResponseEvent)}\n\n`
    );
    res.end();
  } catch (error) {
    logger.debug("Failed to close unavailable response stream", {
      error: error instanceof Error ? error.message : String(error),
    });
  }
}
