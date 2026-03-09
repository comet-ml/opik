import { beforeEach, describe, expect, it, vi } from "vitest";
import { runMigrations, Migration } from "./engine";

const TRACKING_KEY = "opik-ls-migrations";

describe("runMigrations", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("runs all migrations and tracks them", () => {
    const run1 = vi.fn();
    const run2 = vi.fn();
    const migrations: Migration[] = [
      { id: "m1", description: "first", run: run1 },
      { id: "m2", description: "second", run: run2 },
    ];

    runMigrations(migrations);

    expect(run1).toHaveBeenCalledOnce();
    expect(run2).toHaveBeenCalledOnce();
    expect(JSON.parse(localStorage.getItem(TRACKING_KEY)!)).toEqual([
      "m1",
      "m2",
    ]);
  });

  it("skips already-completed migrations", () => {
    localStorage.setItem(TRACKING_KEY, JSON.stringify(["m1"]));
    const run1 = vi.fn();
    const run2 = vi.fn();

    runMigrations([
      { id: "m1", description: "first", run: run1 },
      { id: "m2", description: "second", run: run2 },
    ]);

    expect(run1).not.toHaveBeenCalled();
    expect(run2).toHaveBeenCalledOnce();
    expect(JSON.parse(localStorage.getItem(TRACKING_KEY)!)).toEqual([
      "m1",
      "m2",
    ]);
  });

  it("does not mark failed migrations as completed", () => {
    const consoleSpy = vi.spyOn(console, "error").mockImplementation(() => {});
    const run1 = vi.fn(() => {
      throw new Error("boom");
    });
    const run2 = vi.fn();

    runMigrations([
      { id: "m1", description: "first", run: run1 },
      { id: "m2", description: "second", run: run2 },
    ]);

    expect(run2).toHaveBeenCalledOnce();
    expect(JSON.parse(localStorage.getItem(TRACKING_KEY)!)).toEqual(["m2"]);
    consoleSpy.mockRestore();
  });

  it("handles corrupt tracking key", () => {
    localStorage.setItem(TRACKING_KEY, "not-json");
    const run = vi.fn();

    runMigrations([{ id: "m1", description: "first", run }]);

    expect(run).toHaveBeenCalledOnce();
  });

  it("handles non-array tracking key", () => {
    localStorage.setItem(TRACKING_KEY, JSON.stringify("string"));
    const run = vi.fn();

    runMigrations([{ id: "m1", description: "first", run }]);

    expect(run).toHaveBeenCalledOnce();
  });

  it("handles missing tracking key", () => {
    const run = vi.fn();

    runMigrations([{ id: "m1", description: "first", run }]);

    expect(run).toHaveBeenCalledOnce();
    expect(JSON.parse(localStorage.getItem(TRACKING_KEY)!)).toEqual(["m1"]);
  });

  it("writes tracking key after each migration (crash-safe)", () => {
    const run1 = vi.fn();
    const run2 = vi.fn(() => {
      // Verify m1 was already tracked before m2 runs
      expect(JSON.parse(localStorage.getItem(TRACKING_KEY)!)).toEqual(["m1"]);
    });

    runMigrations([
      { id: "m1", description: "first", run: run1 },
      { id: "m2", description: "second", run: run2 },
    ]);
  });
});
