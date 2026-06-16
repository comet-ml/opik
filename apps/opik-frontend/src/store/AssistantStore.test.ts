import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import useAssistantStore, { MAX_CONCURRENT_EXPLAINS } from "./AssistantStore";
import { ExplainTarget } from "@/types/assistant-sidebar";

const target: ExplainTarget = {
  kind: "trace.error",
  entityId: "trace-1",
  projectId: "proj-1",
  payload: { exception_type: "ValueError" },
};

const READY = { bridgeVersion: 2, capabilities: ["explain"] };

/** Reset the singleton's mutable slices (partial merge keeps the actions). */
function resetStore() {
  useAssistantStore.setState({
    status: "unavailable",
    capabilities: [],
    consoleEmit: null,
    emitQueue: [],
    explains: {},
  });
}

/** Register an emitter and complete the console handshake so emits go out
 * immediately. Returns the emit spy. */
function makeReady() {
  const emit = vi.fn();
  useAssistantStore.getState().registerConsoleEmit(emit);
  useAssistantStore.getState().applyConsoleReady({ ...READY });
  return emit;
}

describe("AssistantStore", () => {
  beforeEach(resetStore);

  describe("console handshake", () => {
    it("applyConsoleReady promotes status to ready and stores capabilities", () => {
      expect(useAssistantStore.getState().status).toBe("unavailable");
      useAssistantStore
        .getState()
        .applyConsoleReady({ bridgeVersion: 2, capabilities: ["explain"] });
      expect(useAssistantStore.getState().status).toBe("ready");
      expect(useAssistantStore.getState().capabilities).toEqual(["explain"]);
    });
  });

  describe("emitToConsole queue + flush", () => {
    it("buffers emits until ready, then flushes them in order", () => {
      const emit = vi.fn();
      useAssistantStore.getState().registerConsoleEmit(emit);

      // Not ready yet -> queued, not emitted.
      useAssistantStore
        .getState()
        .emitToConsole("chat:continue", { question: "q", answer: "a", target });
      useAssistantStore
        .getState()
        .emitToConsole("explain:cancel", { explainId: "x" });
      expect(emit).not.toHaveBeenCalled();

      useAssistantStore.getState().applyConsoleReady({ ...READY });

      expect(emit).toHaveBeenCalledTimes(2);
      expect(emit).toHaveBeenNthCalledWith(1, "chat:continue", {
        question: "q",
        answer: "a",
        target,
      });
      expect(emit).toHaveBeenNthCalledWith(2, "explain:cancel", {
        explainId: "x",
      });
      expect(useAssistantStore.getState().emitQueue).toHaveLength(0);
    });

    it("emits immediately once ready", () => {
      const emit = makeReady();
      useAssistantStore
        .getState()
        .emitToConsole("explain:cancel", { explainId: "y" });
      expect(emit).toHaveBeenCalledWith("explain:cancel", { explainId: "y" });
    });
  });

  describe("ownership guard", () => {
    it("unregister by a non-owner is a no-op", () => {
      const owner = vi.fn();
      const stale = vi.fn();
      useAssistantStore.getState().registerConsoleEmit(owner);
      useAssistantStore.getState().unregisterConsoleEmit(stale);
      expect(useAssistantStore.getState().consoleEmit).toBe(owner);
    });

    it("unregister by the owner tears down the channel and resets status", () => {
      const owner = makeReady();
      useAssistantStore.getState().unregisterConsoleEmit(owner);
      const state = useAssistantStore.getState();
      expect(state.consoleEmit).toBeNull();
      expect(state.status).toBe("unavailable");
      expect(state.capabilities).toEqual([]);
    });

    it("unregister by the owner drops in-flight explains so cap slots free up", () => {
      const owner = makeReady();
      const id = useAssistantStore.getState().runExplain(target)!;
      expect(useAssistantStore.getState().explains[id]).toBeDefined();
      useAssistantStore.getState().unregisterConsoleEmit(owner);
      expect(useAssistantStore.getState().explains).toEqual({});
    });
  });

  describe("runExplain + concurrency cap", () => {
    beforeEach(makeReady);

    it("mints an id, starts in thinking, and emits explain:run", () => {
      const emit = vi.fn();
      useAssistantStore.getState().registerConsoleEmit(emit);
      const id = useAssistantStore.getState().runExplain(target);
      expect(id).toBeTruthy();
      expect(useAssistantStore.getState().explains[id!].phase).toBe("thinking");
      expect(emit).toHaveBeenCalledWith("explain:run", {
        explainId: id,
        target,
      });
    });

    it("returns null once MAX_CONCURRENT_EXPLAINS are in flight", () => {
      const ids = Array.from({ length: MAX_CONCURRENT_EXPLAINS }, () =>
        useAssistantStore.getState().runExplain(target),
      );
      expect(ids.every(Boolean)).toBe(true);
      expect(useAssistantStore.getState().runExplain(target)).toBeNull();
    });

    it("frees a cap slot when an explain finishes", () => {
      const ids = Array.from(
        { length: MAX_CONCURRENT_EXPLAINS },
        () => useAssistantStore.getState().runExplain(target)!,
      );
      useAssistantStore.getState().applyExplainDone({ explainId: ids[0] });
      expect(useAssistantStore.getState().runExplain(target)).toBeTruthy();
    });

    it("frees a cap slot when an explain is cancelled", () => {
      const ids = Array.from(
        { length: MAX_CONCURRENT_EXPLAINS },
        () => useAssistantStore.getState().runExplain(target)!,
      );
      expect(useAssistantStore.getState().runExplain(target)).toBeNull();
      useAssistantStore.getState().cancelExplain(ids[0]);
      expect(useAssistantStore.getState().runExplain(target)).toBeTruthy();
    });
  });

  describe("explain stream transitions", () => {
    let id: string;
    beforeEach(() => {
      makeReady();
      id = useAssistantStore.getState().runExplain(target)!;
    });

    it("chunks accumulate text and move to streaming", () => {
      useAssistantStore
        .getState()
        .applyExplainChunk({ explainId: id, delta: "Hello " });
      useAssistantStore
        .getState()
        .applyExplainChunk({ explainId: id, delta: "world" });
      const explain = useAssistantStore.getState().explains[id];
      expect(explain.phase).toBe("streaming");
      expect(explain.text).toBe("Hello world");
    });

    it("done moves to done; an empty body stays done with empty text", () => {
      useAssistantStore.getState().applyExplainDone({ explainId: id });
      const explain = useAssistantStore.getState().explains[id];
      expect(explain.phase).toBe("done");
      expect(explain.text).toBe("");
    });

    it("error sets the error phase and message", () => {
      useAssistantStore
        .getState()
        .applyExplainError({ explainId: id, message: "boom" });
      const explain = useAssistantStore.getState().explains[id];
      expect(explain.phase).toBe("error");
      expect(explain.error).toBe("boom");
    });

    it("ignores deltas for an unknown / already-cleared id (cancel race)", () => {
      useAssistantStore.getState().clearExplain(id);
      useAssistantStore
        .getState()
        .applyExplainChunk({ explainId: id, delta: "late" });
      expect(useAssistantStore.getState().explains[id]).toBeUndefined();
    });

    it("applyExplainDone ignores an unknown / already-cleared id", () => {
      useAssistantStore.getState().clearExplain(id);
      useAssistantStore.getState().applyExplainDone({ explainId: id });
      expect(useAssistantStore.getState().explains[id]).toBeUndefined();
    });

    it("applyExplainError ignores an unknown / already-cleared id", () => {
      useAssistantStore.getState().clearExplain(id);
      useAssistantStore
        .getState()
        .applyExplainError({ explainId: id, message: "late" });
      expect(useAssistantStore.getState().explains[id]).toBeUndefined();
    });

    it("cancelExplain emits explain:cancel and drops the entry", () => {
      const emit = vi.fn();
      useAssistantStore.getState().registerConsoleEmit(emit);
      useAssistantStore.getState().cancelExplain(id);
      expect(emit).toHaveBeenCalledWith("explain:cancel", { explainId: id });
      expect(useAssistantStore.getState().explains[id]).toBeUndefined();
    });

    it("clearExplain removes a finished entry and is idempotent", () => {
      useAssistantStore.getState().applyExplainDone({ explainId: id });
      useAssistantStore.getState().clearExplain(id);
      useAssistantStore.getState().clearExplain(id);
      expect(useAssistantStore.getState().explains[id]).toBeUndefined();
    });
  });
});

// FE-1a regression guard: the open/width slice MUST seed from the same
// localStorage keys the iframe console writes, or width/open is lost across
// remounts and refreshes (VER-9).
function createLocalStorageMock(): Storage {
  let data: Record<string, string> = {};
  return {
    getItem: (key) => (key in data ? data[key] : null),
    setItem: (key, value) => {
      data[key] = String(value);
    },
    removeItem: (key) => {
      delete data[key];
    },
    clear: () => {
      data = {};
    },
    key: (index) => Object.keys(data)[index] ?? null,
    get length() {
      return Object.keys(data).length;
    },
  } as Storage;
}

describe("AssistantStore sidebar seed (FE-1a)", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.stubGlobal("localStorage", createLocalStorageMock());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("defaults to the open default width when nothing is stored", async () => {
    const { default: store } = await import("./AssistantStore");
    const { ASSISTANT_SIDEBAR_DEFAULT_WIDTH } = await import(
      "@/constants/assistantSidebar"
    );
    expect(store.getState().sidebarWidth).toBe(ASSISTANT_SIDEBAR_DEFAULT_WIDTH);
  });

  it("seeds the stored width when the sidebar was left open", async () => {
    localStorage.setItem("assistant-sidebar-open", "true");
    localStorage.setItem("assistant-sidebar-width", "512");
    const { default: store } = await import("./AssistantStore");
    expect(store.getState().sidebarWidth).toBe(512);
  });

  it("seeds the collapsed width when the sidebar was left closed", async () => {
    localStorage.setItem("assistant-sidebar-open", "false");
    localStorage.setItem("assistant-sidebar-width", "512");
    const { default: store } = await import("./AssistantStore");
    const { ASSISTANT_SIDEBAR_COLLAPSED_WIDTH } = await import(
      "@/constants/assistantSidebar"
    );
    expect(store.getState().sidebarWidth).toBe(
      ASSISTANT_SIDEBAR_COLLAPSED_WIDTH,
    );
  });
});
