import fs from "node:fs";

import { describe, expect, it } from "vitest";

import {
  getCompletionScript,
  normalizeCursorMcpConfig,
  runCli,
} from "@/tui";

describe("tui cli", () => {
  it("normalizes cursor mcp config", () => {
    const tools = normalizeCursorMcpConfig({
      mcpServers: {
        context7: {
          url: "https://mcp.context7.com/mcp",
        },
      },
    });

    expect(tools).toHaveLength(1);
    expect(tools[0]).toMatchObject({
      type: "mcp",
      server_label: "context7",
      server_url: "https://mcp.context7.com/mcp",
    });
  });

  it("emits status as json", () => {
    const out: string[] = [];
    const err: string[] = [];

    const code = runCli(["tui", "status", "--json"], {
      out: (line) => out.push(line),
      err: (line) => err.push(line),
    });

    expect(code).toBe(0);
    expect(err).toHaveLength(0);
    expect(JSON.parse(out[0] ?? "{}")).toMatchObject({ event: "status" });
  });

  it("normalizes tools via cli command", () => {
    const tempPath = `${process.cwd()}/.tmp-tui-cursor-config.json`;
    fs.writeFileSync(
      tempPath,
      JSON.stringify({
        mcpServers: {
          context7: {
            url: "https://mcp.context7.com/mcp",
          },
        },
      }),
      "utf-8",
    );

    try {
      const out: string[] = [];
      const code = runCli(
        ["tui", "normalize-tools", "--cursor-config", tempPath, "--json"],
        {
          out: (line) => out.push(line),
          err: () => undefined,
        },
      );

      expect(code).toBe(0);
      expect(JSON.parse(out[0] ?? "{}")).toMatchObject({
        event: "normalized_tools",
      });
    } finally {
      fs.unlinkSync(tempPath);
    }
  });

  it("renders bash completion script", () => {
    const script = getCompletionScript("bash", "opik-tui");
    expect(script).toContain("complete -F _opik_tui_complete opik-tui");
  });
});
