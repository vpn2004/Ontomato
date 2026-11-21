import { afterEach, expect, it, vi } from "vitest";
const io = vi.hoisted(() => ({ timeout: 0 }));
vi.mock("@langchain/openai", () => ({ ChatOpenAI: class {
  constructor(options: any) { io.timeout = options.timeout; }
  async *stream(_messages: unknown, { signal }: { signal: AbortSignal }) {
    await new Promise((resolve) => setTimeout(resolve, 180_000));
    signal.throwIfAborted();
    yield { content: "Completed long assessment", response_metadata: { finish_reason: "stop" } };
  }
} }));
vi.mock("../../../utils/langchain-token-counter", () => ({ useApproximateTokenCounter: (model: unknown) => model }));
import { streamChatCompletion } from "../llm-stream";
afterEach(() => { vi.clearAllTimers(); vi.useRealTimers(); vi.restoreAllMocks(); });
it("honors the caller's model budget instead of aborting a valid long call at two minutes", async () => {
  vi.useFakeTimers();
  vi.spyOn(AbortSignal, "timeout").mockImplementation((ms) => {
    const controller = new AbortController(); setTimeout(() => controller.abort(), ms); return controller.signal;
  });
  const done = (async () => {
    const stream = streamChatCompletion({ baseUrl: "http://model", apiKey: "test", modelName: "test", timeoutMs: 300_000 }, "Review", [], []);
    let next = await stream.next(); while (!next.done) next = await stream.next(); return next.value;
  })();
  await vi.advanceTimersByTimeAsync(180_000);
  const result = await done;
  expect(io.timeout).toBe(300_000);
  expect(result.stopReason).toBe("stop");
  expect(result.content).toContainEqual({ type: "text", text: "Completed long assessment" });
});
