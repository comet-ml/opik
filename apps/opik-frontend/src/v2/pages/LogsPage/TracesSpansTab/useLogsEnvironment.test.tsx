import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryParamProvider } from "use-query-params";
import { WindowHistoryAdapter } from "use-query-params/adapters/window";
import { ENVIRONMENT_UNTAGGED_VALUE } from "@/lib/filters";
import { useLogsEnvironment } from "./useLogsEnvironment";

let mockEnvironments: Array<{ name: string }> | undefined;

vi.mock("@/api/environments/useEnvironmentsList", () => ({
  default: vi.fn(() => ({
    data: mockEnvironments ? { content: mockEnvironments } : undefined,
  })),
}));

const wrapper = ({ children }: { children: React.ReactNode }) => (
  <QueryParamProvider adapter={WindowHistoryAdapter}>
    {children}
  </QueryParamProvider>
);

const MEMORY_KEY = "logs-environment:p1";

const setUrl = (params: Record<string, string>) => {
  const search = new URLSearchParams(params).toString();
  window.history.replaceState({}, "", `/logs${search ? `?${search}` : ""}`);
};

const readUrlEnvironment = () =>
  new URLSearchParams(window.location.search).get("environment");

const readRemembered = () => {
  const raw = localStorage.getItem(MEMORY_KEY);
  return raw ? JSON.parse(raw) : undefined;
};

// The window adapter doesn't re-render on its own; rerender() stands in for the router.
const setup = (canRestore = true) =>
  renderHook(() => useLogsEnvironment("p1", { canRestore }), { wrapper });

describe("useLogsEnvironment", () => {
  beforeEach(() => {
    localStorage.clear();
    mockEnvironments = [{ name: "prod" }, { name: "staging" }];
    setUrl({});
  });

  describe("restore", () => {
    it("writes the saved environment to the URL when the param is absent", async () => {
      localStorage.setItem(MEMORY_KEY, JSON.stringify("prod"));
      const { result, rerender } = setup();

      await waitFor(() => expect(readUrlEnvironment()).toBe("prod"));
      rerender();
      expect(result.current.environment).toBe("prod");
    });

    it("returns the restored environment on the first render", () => {
      localStorage.setItem(MEMORY_KEY, JSON.stringify("prod"));
      const renders: string[] = [];
      renderHook(
        () => {
          const hook = useLogsEnvironment("p1", { canRestore: true });
          renders.push(hook.environment);
          return hook;
        },
        { wrapper },
      );

      expect(renders[0]).toBe("prod");
    });

    it("does not restore when canRestore is false (URL arrived with filters)", () => {
      localStorage.setItem(MEMORY_KEY, JSON.stringify("prod"));
      const { result, rerender } = setup(false);
      rerender();

      expect(result.current.environment).toBe("");
      expect(readUrlEnvironment()).toBeNull();
      expect(readRemembered()).toBe("prod");
    });

    it("does not restore over an environment that arrives in the URL", () => {
      localStorage.setItem(MEMORY_KEY, JSON.stringify("prod"));
      setUrl({ environment: "staging" });
      const { result } = setup();

      expect(result.current.environment).toBe("staging");
      expect(readUrlEnvironment()).toBe("staging");
      expect(readRemembered()).toBe("prod");
    });

    it("does not restore another project's environment", () => {
      localStorage.setItem("logs-environment:other", JSON.stringify("prod"));
      const { result } = setup();

      expect(result.current.environment).toBe("");
      expect(readUrlEnvironment()).toBeNull();
    });

    it("restores when the param disappears without a remount", async () => {
      localStorage.setItem(MEMORY_KEY, JSON.stringify("prod"));
      setUrl({ environment: "staging" });
      const { rerender } = setup();
      expect(readUrlEnvironment()).toBe("staging");

      setUrl({});
      rerender();

      await waitFor(() => expect(readUrlEnvironment()).toBe("prod"));
    });
  });

  describe("changeEnvironment", () => {
    it("saves and writes the picked environment", async () => {
      const { result } = setup();

      await act(async () => result.current.changeEnvironment("staging"));

      expect(readRemembered()).toBe("staging");
      expect(readUrlEnvironment()).toBe("staging");
    });

    it("forgets the environment when cleared and does not restore it", async () => {
      localStorage.setItem(MEMORY_KEY, JSON.stringify("prod"));
      const { result, rerender } = setup();
      await waitFor(() => expect(readUrlEnvironment()).toBe("prod"));
      rerender();

      await act(async () => result.current.changeEnvironment(""));
      rerender();

      expect(readRemembered()).toBeUndefined();
      expect(readUrlEnvironment()).toBeNull();
      expect(result.current.environment).toBe("");
    });
  });

  describe("invalid environment", () => {
    it("forgets an invalid remembered value without looping back", async () => {
      localStorage.setItem(MEMORY_KEY, JSON.stringify("deleted-env"));
      const { result, rerender } = setup();

      await waitFor(() => expect(readRemembered()).toBeUndefined());
      rerender();
      expect(result.current.environment).toBe("");
      expect(result.current.envIsValid).toBeNull();
      expect(readUrlEnvironment()).toBeNull();
    });

    it.each([
      ["an object", { name: "prod" }],
      ["an array", ["prod"]],
    ])(
      "ignores and clears a remembered value that is %s, without looping",
      async (_label, stored) => {
        localStorage.setItem(MEMORY_KEY, JSON.stringify(stored));
        let renders = 0;
        const { result } = renderHook(
          () => {
            renders += 1;
            return useLogsEnvironment("p1", { canRestore: true });
          },
          { wrapper },
        );

        await waitFor(() =>
          expect(localStorage.getItem(MEMORY_KEY)).toBeNull(),
        );
        expect(result.current.environment).toBe("");
        expect(result.current.envIsValid).toBeNull();
        expect(readUrlEnvironment()).toBeNull();
        expect(renders).toBeLessThan(5);
      },
    );

    it("clears an invalid environment that arrived in the URL", async () => {
      setUrl({ environment: "deleted-env" });
      setup();

      await waitFor(() => expect(readUrlEnvironment()).toBeNull());
    });

    it("does not wipe a different remembered environment", async () => {
      localStorage.setItem(MEMORY_KEY, JSON.stringify("prod"));
      setUrl({ environment: "deleted-env" });
      setup(false);

      await waitFor(() => expect(readUrlEnvironment()).toBeNull());
      expect(readRemembered()).toBe("prod");
    });

    it("waits for the environments list before judging validity", () => {
      mockEnvironments = undefined;
      setUrl({ environment: "deleted-env" });
      const { result } = setup();

      expect(result.current.envIsValid).toBeNull();
      expect(readUrlEnvironment()).toBe("deleted-env");
    });

    it("keeps the untagged value", () => {
      setUrl({ environment: ENVIRONMENT_UNTAGGED_VALUE });
      const { result } = setup();

      expect(result.current.envIsValid).toBe(true);
      expect(readUrlEnvironment()).toBe(ENVIRONMENT_UNTAGGED_VALUE);
    });
  });
});
