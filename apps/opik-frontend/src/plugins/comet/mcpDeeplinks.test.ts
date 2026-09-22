import { describe, it, expect, vi } from "vitest";

vi.mock("@/api/api", () => ({ BASE_API_URL: "/api" }));

import {
  claudeCodeDeeplink,
  codexCommand,
  cursorDeeplink,
  vscodeDeeplink,
} from "./mcpDeeplinks";

// The links the README and apps/opik-documentation .../mcp-server.mdx ship,
// against the production server. A route that stops matching these is a route
// that stopped opening anything.
const SERVER_URL = "https://www.comet.com/opik/api/v1/mcp";
const SHIPPED_CURSOR =
  "cursor:////anysphere.cursor-deeplink/mcp/install?name=opik-mcp&config=eyJ1cmwiOiJodHRwczovL3d3dy5jb21ldC5jb20vb3Bpay9hcGkvdjEvbWNwIn0=";
const SHIPPED_VSCODE =
  "https://insiders.vscode.dev/redirect/mcp/install?name=opik-mcp&config=%7B%22type%22%3A%22http%22%2C%22url%22%3A%22https%3A%2F%2Fwww.comet.com%2Fopik%2Fapi%2Fv1%2Fmcp%22%7D";

describe("the hosted install routes", () => {
  it("build the Cursor link the docs ship", () => {
    expect(cursorDeeplink(SERVER_URL)).toBe(SHIPPED_CURSOR);
  });

  it("build the VS Code link the docs ship", () => {
    expect(vscodeDeeplink(SERVER_URL)).toBe(SHIPPED_VSCODE);
  });

  it("send VS Code through the redirector, not the vscode: scheme", () => {
    // The scheme needs an OS handler the page cannot count on, which is why
    // `vscode:mcp/install?…` silently did nothing.
    expect(vscodeDeeplink(SERVER_URL)).not.toMatch(/^vscode(-insiders)?:/);
  });

  it("carry this deployment's own server, not a hard-coded one", () => {
    const local = "http://localhost:5173/api/v1/mcp";

    for (const link of [
      cursorDeeplink(local),
      vscodeDeeplink(local),
      claudeCodeDeeplink(local),
      codexCommand(local),
    ]) {
      expect(link).not.toContain("www.comet.com");
    }

    expect(
      atob(new URL(cursorDeeplink(local)).searchParams.get("config")!),
    ).toBe(JSON.stringify({ url: local }));
    expect(new URL(vscodeDeeplink(local)).searchParams.get("config")).toBe(
      JSON.stringify({ type: "http", url: local }),
    );
  });

  it("give the terminal clients a command that names the server", () => {
    expect(codexCommand(SERVER_URL)).toBe(
      `codex mcp add opik-mcp --url ${SERVER_URL}`,
    );
    expect(claudeCodeDeeplink(SERVER_URL)).toContain(
      encodeURIComponent(
        `!claude mcp add --transport http --scope user opik-mcp ${SERVER_URL}`,
      ),
    );
  });
});
