import { describe, expect, it } from "vitest";
import { isAiSpendRoute } from "./aiSpend";

describe("isAiSpendRoute", () => {
  it("matches the ai-spend area and its subroutes", () => {
    expect(isAiSpendRoute("/ws/ai-spend")).toBe(true);
    expect(isAiSpendRoute("/ws/ai-spend/")).toBe(true);
    expect(isAiSpendRoute("/ws/ai-spend/leaderboard")).toBe(true);
  });

  it("does not match unrelated routes", () => {
    expect(isAiSpendRoute("/ws/home")).toBe(false);
    expect(isAiSpendRoute("/ws/ai-spendx")).toBe(false);
  });
});
