import { describe, expect, it, vi } from "vitest";

import { runCli } from "@/cli";
import type { OpikClient } from "@/client/Client";

describe("opik query cli", () => {
  it("returns projects in json mode", async () => {
    const out: string[] = [];

    const fakeClient = {
      api: {
        projects: {
          findProjects: vi.fn().mockResolvedValue({
            content: [{ id: "p1", name: "project-1" }],
          }),
        },
      },
    };

    const code = await runCli(
      ["query", "projects", "--json"],
      { out: (line) => out.push(line), err: () => undefined },
      { createClient: () => fakeClient as unknown as OpikClient }
    );

    expect(code).toBe(0);
    const payload = JSON.parse(out[0] ?? "{}");
    expect(payload.event).toBe("projects");
    expect(payload.payload.count).toBe(1);
  });

  it("returns dataset by name", async () => {
    const out: string[] = [];

    const fakeClient = {
      getDataset: vi.fn().mockResolvedValue({ id: "d1", name: "dataset-1" }),
    };

    const code = await runCli(
      ["query", "dataset", "--name", "dataset-1", "--json"],
      { out: (line) => out.push(line), err: () => undefined },
      { createClient: () => fakeClient as unknown as OpikClient }
    );

    expect(code).toBe(0);
    const payload = JSON.parse(out[0] ?? "{}");
    expect(payload.event).toBe("dataset");
    expect(payload.payload.name).toBe("dataset-1");
  });

  it("returns prompt by name", async () => {
    const out: string[] = [];

    const fakeClient = {
      getPrompt: vi.fn().mockResolvedValue({ name: "prompt-1" }),
    };

    const code = await runCli(
      ["query", "prompt", "--name", "prompt-1", "--json"],
      { out: (line) => out.push(line), err: () => undefined },
      { createClient: () => fakeClient as unknown as OpikClient }
    );

    expect(code).toBe(0);
    const payload = JSON.parse(out[0] ?? "{}");
    expect(payload.event).toBe("prompt");
    expect(payload.payload.name).toBe("prompt-1");
  });

  it("prints completion script", async () => {
    const out: string[] = [];

    const fakeClient = {};

    const code = await runCli(
      ["query", "completion", "--shell", "bash"],
      { out: (line) => out.push(line), err: () => undefined },
      { createClient: () => fakeClient as unknown as OpikClient }
    );

    expect(code).toBe(0);
    expect(out[0]).toContain("complete -F _opik_complete opik");
  });
});
