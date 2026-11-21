import { afterEach, describe, expect, it, vi } from "vitest";
import { createProgressBudget } from "../progress-budget";

afterEach(() => { vi.useRealTimers(); });
describe("productive query budget", () => {
  it("permits a productive query past ten minutes and stops a stalled one", async () => {
    vi.useFakeTimers();
    const expire = vi.fn();
    const budget = createProgressBudget(600_000, 1_800_000, expire);
    await vi.advanceTimersByTimeAsync(500_000);
    budget.progress("dataset-1");
    await vi.advanceTimersByTimeAsync(400_000);
    expect(expire).not.toHaveBeenCalled();
    budget.progress("dataset-1");
    await vi.advanceTimersByTimeAsync(200_000);
    expect(expire).toHaveBeenCalledOnce();
    budget.dispose();
    expect(vi.getTimerCount()).toBe(0);
  });
  it("retains an overall bound even with continuing new results", async () => {
    vi.useFakeTimers();
    const expire = vi.fn();
    const budget = createProgressBudget(600_000, 1_800_000, expire);
    for (let i = 0; i < 4; i++) {
      await vi.advanceTimersByTimeAsync(450_000);
      budget.progress(`dataset-${i}`);
    }
    expect(expire).toHaveBeenCalledOnce();
    budget.dispose();
    expect(vi.getTimerCount()).toBe(0);
  });
});
