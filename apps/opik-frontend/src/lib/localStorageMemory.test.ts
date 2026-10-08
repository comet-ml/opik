import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { createLocalStorageMemory } from "./localStorageMemory";

describe("createLocalStorageMemory", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("returns undefined when nothing is saved", () => {
    expect(createLocalStorageMemory<string>("k").load()).toBeUndefined();
  });

  it("round-trips JSON values", () => {
    const memory = createLocalStorageMemory<{ a: number[] }>("k");
    memory.save({ a: [1, 2] });
    expect(memory.load()).toEqual({ a: [1, 2] });
    expect(window.localStorage.getItem("k")).toBe('{"a":[1,2]}');
  });

  it("isolates keys", () => {
    const first = createLocalStorageMemory<string>("first");
    const second = createLocalStorageMemory<string>("second");
    first.save("one");
    expect(second.load()).toBeUndefined();
    second.save("two");
    expect(first.load()).toBe("one");
    expect(second.load()).toBe("two");
  });

  it.each([
    ["undefined", undefined],
    ["an empty array", []],
    ["an empty string", ""],
  ])("removes the key when saving %s", (_label, empty) => {
    const memory = createLocalStorageMemory<unknown>("k");
    memory.save("value");
    memory.save(empty);
    expect(window.localStorage.getItem("k")).toBeNull();
    expect(memory.load()).toBeUndefined();
  });

  it("returns undefined for corrupted JSON", () => {
    window.localStorage.setItem("k", "{not json");
    expect(createLocalStorageMemory<string>("k").load()).toBeUndefined();
  });

  it("does not throw when localStorage throws", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "removeItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    const memory = createLocalStorageMemory<string>("k");

    expect(() => memory.save("value")).not.toThrow();
    expect(() => memory.save(undefined)).not.toThrow();
    expect(memory.load()).toBeUndefined();
  });
});
