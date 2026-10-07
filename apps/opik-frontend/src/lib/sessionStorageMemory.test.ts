import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { createSessionStorageMemory } from "./sessionStorageMemory";

describe("createSessionStorageMemory", () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("returns undefined when nothing is saved", () => {
    expect(createSessionStorageMemory<string>("k").load()).toBeUndefined();
  });

  it("round-trips JSON values", () => {
    const memory = createSessionStorageMemory<{ a: number[] }>("k");
    memory.save({ a: [1, 2] });
    expect(memory.load()).toEqual({ a: [1, 2] });
    expect(window.sessionStorage.getItem("k")).toBe('{"a":[1,2]}');
  });

  it("isolates keys", () => {
    const first = createSessionStorageMemory<string>("first");
    const second = createSessionStorageMemory<string>("second");
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
    const memory = createSessionStorageMemory<unknown>("k");
    memory.save("value");
    memory.save(empty);
    expect(window.sessionStorage.getItem("k")).toBeNull();
    expect(memory.load()).toBeUndefined();
  });

  it("returns undefined for corrupted JSON", () => {
    window.sessionStorage.setItem("k", "{not json");
    expect(createSessionStorageMemory<string>("k").load()).toBeUndefined();
  });

  it("does not throw when sessionStorage throws", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "removeItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    const memory = createSessionStorageMemory<string>("k");

    expect(() => memory.save("value")).not.toThrow();
    expect(() => memory.save(undefined)).not.toThrow();
    expect(memory.load()).toBeUndefined();
  });
});
