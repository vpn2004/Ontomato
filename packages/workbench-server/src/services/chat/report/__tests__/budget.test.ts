import { afterEach, describe, expect, it, vi } from "vitest";
import { createReportBudget } from "../budget";

afterEach(() => { vi.useRealTimers(); });

describe("separate report reasoning and collection budgets", () => {
  it("lets a long bounded collection finish without consuming the report reasoning allowance", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date", "performance"] });
    const budget = createReportBudget({ reasoningMs: 20_000, totalMs: 60_000 });
    try {
      await vi.advanceTimersByTimeAsync(5_000);
      const collection = budget.collect(() => new Promise<string>((resolve) => setTimeout(() => resolve("evidence"), 30_000)));
      await vi.advanceTimersByTimeAsync(30_000);
      expect(await collection).toBe("evidence");
      expect(budget.signal.aborted).toBe(false);
      await vi.advanceTimersByTimeAsync(14_999);
      expect(budget.signal.aborted).toBe(false);
      await vi.advanceTimersByTimeAsync(1);
      expect(budget.signal.aborted).toBe(true);
      expect(budget.signal.reason.message).toContain("reasoning");
    } finally { budget.dispose(); }
  });
  it("keeps an overall bound even while a collection is still pending", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date", "performance"] });
    const budget = createReportBudget({ reasoningMs: 20_000, totalMs: 60_000 });
    let finish!: () => void;
    try {
      const collection = budget.collect(() => new Promise<void>((resolve) => { finish = resolve; }));
      const rejected = expect(collection).rejects.toThrow("total");
      await vi.advanceTimersByTimeAsync(60_000);
      expect(budget.signal.aborted).toBe(true);
      expect(budget.signal.reason.message).toContain("total");
      await rejected;
      finish();
    } finally { budget.dispose(); }
  });
  it("returns promptly on caller cancellation even if the collector ignores its signal", async () => {
    const budget = createReportBudget();
    const caller = new AbortController();
    let finish!: (value: string) => void;
    try {
      const collection = budget.collect(() => new Promise<string>((resolve) => { finish = resolve; }), caller.signal);
      const rejected = expect(collection).rejects.toThrow("User stopped");
      await Promise.resolve();
      caller.abort(new Error("User stopped"));
      await rejected;
      finish("Late evidence must not be returned");
    } finally { budget.dispose(); }
  });
  it("resumes the remaining reasoning allowance after a failed collection", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date", "performance"] });
    const budget = createReportBudget({ reasoningMs: 20_000, totalMs: 60_000 });
    try {
      await vi.advanceTimersByTimeAsync(10_000);
      await expect(budget.collect(async () => { throw new Error("Source failed"); })).rejects.toThrow("Source failed");
      await vi.advanceTimersByTimeAsync(10_000);
      expect(budget.signal.aborted).toBe(true);
      expect(budget.signal.reason.message).toContain("reasoning");
    } finally { budget.dispose(); }
  });
  it("does not invoke a collection after expiration and clears its timers on disposal", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date", "performance"] });
    const budget = createReportBudget({ reasoningMs: 20_000, totalMs: 60_000 });
    await vi.advanceTimersByTimeAsync(20_000);
    const collect = vi.fn();
    await expect(budget.collect(collect)).rejects.toThrow();
    expect(collect).not.toHaveBeenCalled();
    budget.dispose();
    expect(vi.getTimerCount()).toBe(0);
  });
});
