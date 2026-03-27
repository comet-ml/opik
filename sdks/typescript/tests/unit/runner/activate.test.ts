import { vi, describe, it, expect, beforeEach, afterEach } from "vitest";
import { createMockHttpResponsePromise } from "@tests/mockUtils";

// --- Module mocks ---

const registerAgentsMock = vi.fn(() => createMockHttpResponsePromise(undefined));

vi.mock("@/client/Client", () => ({
  OpikClient: vi.fn(() => ({
    api: {
      runners: {
        registerAgents: registerAgentsMock,
      },
    },
    config: { projectName: "default" },
    flush: vi.fn(() => Promise.resolve()),
  })),
}));

vi.mock("@/runner/InProcessRunnerLoop", () => ({
  InProcessRunnerLoop: vi.fn(() => ({
    start: vi.fn(),
    shutdown: vi.fn(),
  })),
}));

vi.mock("@/runner/prefixedOutput", () => ({
  installPrefixedOutput: vi.fn(),
}));

// Registry is real — we need actual register/onRegister/getAll behaviour.
// Re-export with a reset helper so each test starts clean.
const registryListeners: ((name: string) => void)[] = [];
const registryMap = new Map<string, object>();

vi.mock("@/runner/registry", async (importOriginal) => {
  const real = await importOriginal<typeof import("@/runner/registry")>();
  return {
    ...real,
    getAll: vi.fn(() => new Map(registryMap)),
    register: vi.fn((entry: { name: string }) => {
      registryMap.set(entry.name, entry);
      registryListeners.forEach((l) => l(entry.name));
    }),
    onRegister: vi.fn((listener: (name: string) => void) => {
      registryListeners.push(listener);
    }),
  };
});

// --- Tests ---

describe("activateRunner", () => {
  const originalEnv = process.env;

  beforeEach(() => {
    vi.resetModules();
    registerAgentsMock.mockClear();
    registerAgentsMock.mockImplementation(() =>
      createMockHttpResponsePromise(undefined)
    );
    registryMap.clear();
    registryListeners.length = 0;

    process.env = {
      ...originalEnv,
      OPIK_RUNNER_MODE: "true",
      OPIK_RUNNER_ID: "runner-test",
      OPIK_PROJECT_NAME: "test-project",
    };
  });

  afterEach(() => {
    process.env = originalEnv;
    vi.restoreAllMocks();
  });

  it("is a no-op when OPIK_RUNNER_MODE is not set", async () => {
    delete process.env.OPIK_RUNNER_MODE;
    const { activateRunner } = await import("@/runner/activate");
    activateRunner();
    await new Promise((r) => setImmediate(r));
    expect(registerAgentsMock).not.toHaveBeenCalled();
  });

  it("registers all agents present at startup in a single call", async () => {
    registryMap.set("agent-a", {
      name: "agent-a",
      func: () => {},
      project: "p",
      params: [],
      docstring: "",
    });
    registryMap.set("agent-b", {
      name: "agent-b",
      func: () => {},
      project: "p",
      params: [],
      docstring: "",
    });

    const { activateRunner } = await import("@/runner/activate");
    activateRunner();
    await new Promise((r) => setImmediate(r));
    await new Promise((r) => setImmediate(r));

    expect(registerAgentsMock).toHaveBeenCalledTimes(1);
    expect(registerAgentsMock).toHaveBeenCalledWith(
      "runner-test",
      expect.objectContaining({
        body: expect.objectContaining({
          "agent-a": expect.anything(),
          "agent-b": expect.anything(),
        }),
      })
    );
  });

  it("sends the full registry when a new agent is registered dynamically", async () => {
    registryMap.set("agent-a", {
      name: "agent-a",
      func: () => {},
      project: "p",
      params: [],
      docstring: "",
    });

    const { activateRunner } = await import("@/runner/activate");
    const { register } = await import("@/runner/registry");
    activateRunner();
    await new Promise((r) => setImmediate(r));
    await new Promise((r) => setImmediate(r));

    registerAgentsMock.mockClear();

    // Dynamically add a second agent
    registryMap.set("agent-b", {
      name: "agent-b",
      func: () => {},
      project: "p",
      params: [],
      docstring: "",
    });
    register({
      name: "agent-b",
      func: () => {},
      project: "p",
      params: [],
      docstring: "",
    });

    await new Promise((r) => setImmediate(r));

    expect(registerAgentsMock).toHaveBeenCalledTimes(1);
    // Must include both existing and newly added agent
    expect(registerAgentsMock).toHaveBeenCalledWith(
      "runner-test",
      expect.objectContaining({
        body: expect.objectContaining({
          "agent-a": expect.anything(),
          "agent-b": expect.anything(),
        }),
      })
    );
  });

  it("does not activate twice when called multiple times", async () => {
    registryMap.set("agent-a", {
      name: "agent-a",
      func: () => {},
      project: "p",
      params: [],
      docstring: "",
    });

    const { activateRunner } = await import("@/runner/activate");
    activateRunner();
    activateRunner();
    activateRunner();

    await new Promise((r) => setImmediate(r));
    await new Promise((r) => setImmediate(r));

    expect(registerAgentsMock).toHaveBeenCalledTimes(1);
  });
});
