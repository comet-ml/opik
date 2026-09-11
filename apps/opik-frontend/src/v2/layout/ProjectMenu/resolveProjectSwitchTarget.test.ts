import { describe, expect, it } from "vitest";
import { resolveProjectSwitchTarget } from "./resolveProjectSwitchTarget";

const WS = "my-ws";
const NEW_PROJECT = "new-project-id";
const CUR = "/my-ws/projects/cur-project";

const match = (pathname: string) => ({ pathname });

const projectChain = [
  match("/"),
  match("/my-ws"),
  match("/my-ws/projects"),
  match(CUR),
];

describe("resolveProjectSwitchTarget", () => {
  it("stays on /logs and preserves allowlisted search params", () => {
    const result = resolveProjectSwitchTarget(
      [...projectChain, match(`${CUR}/logs`)],
      { logsType: "threads", size: 50 },
      WS,
      NEW_PROJECT,
    );
    expect(result).toEqual({
      to: "/$workspaceName/projects/$projectId/logs",
      params: { workspaceName: WS, projectId: NEW_PROJECT },
      search: { logsType: "threads", size: 50 },
    });
  });

  it("drops disallowed search params on /logs", () => {
    const result = resolveProjectSwitchTarget(
      [...projectChain, match(`${CUR}/logs`)],
      { trace: "abc-123", traces_filters: "[]", page: 5 },
      WS,
      NEW_PROJECT,
    );
    expect(result.to).toBe("/$workspaceName/projects/$projectId/logs");
    expect(result.search).toEqual({});
  });

  it("stays on /dashboards (empty allowlist) with empty search", () => {
    const result = resolveProjectSwitchTarget(
      [...projectChain, match(`${CUR}/dashboards`)],
      { anything: "x" },
      WS,
      NEW_PROJECT,
    );
    expect(result).toEqual({
      to: "/$workspaceName/projects/$projectId/dashboards",
      params: { workspaceName: WS, projectId: NEW_PROJECT },
      search: {},
    });
  });

  it("preserves size on /experiments list", () => {
    const result = resolveProjectSwitchTarget(
      [...projectChain, match(`${CUR}/experiments`)],
      { size: 25 },
      WS,
      NEW_PROJECT,
    );
    expect(result.to).toBe("/$workspaceName/projects/$projectId/experiments");
    expect(result.search).toEqual({ size: 25 });
  });

  it("trims /experiments/<id>/compare back to /experiments and drops search", () => {
    const result = resolveProjectSwitchTarget(
      [...projectChain, match(`${CUR}/experiments/ds-abc/compare`)],
      { compare: "a,b", size: 25 },
      WS,
      NEW_PROJECT,
    );
    expect(result.to).toBe("/$workspaceName/projects/$projectId/experiments");
    expect(result.search).toEqual({ size: 25 });
  });

  it("trims /datasets/<id>/items back to /datasets", () => {
    const result = resolveProjectSwitchTarget(
      [...projectChain, match(`${CUR}/datasets/ds-xyz/items`)],
      { row: "abc", size: 50 },
      WS,
      NEW_PROJECT,
    );
    expect(result.to).toBe("/$workspaceName/projects/$projectId/datasets");
    expect(result.search).toEqual({ size: 50 });
  });

  it("trims /alerts/new back to /alerts and drops search", () => {
    const result = resolveProjectSwitchTarget(
      [...projectChain, match(`${CUR}/alerts/new`)],
      { foo: 1 },
      WS,
      NEW_PROJECT,
    );
    expect(result.to).toBe("/$workspaceName/projects/$projectId/alerts");
    expect(result.search).toEqual({});
  });

  it("trims /alerts/<id> back to /alerts", () => {
    const result = resolveProjectSwitchTarget(
      [...projectChain, match(`${CUR}/alerts/alert-1`)],
      { anything: 1 },
      WS,
      NEW_PROJECT,
    );
    expect(result.to).toBe("/$workspaceName/projects/$projectId/alerts");
    expect(result.search).toEqual({});
  });

  it("trims /optimizations/<id>/trials back to /optimizations", () => {
    const result = resolveProjectSwitchTarget(
      [...projectChain, match(`${CUR}/optimizations/opt-1/trials`)],
      { trialNumber: 3, size: 10 },
      WS,
      NEW_PROJECT,
    );
    expect(result.to).toBe("/$workspaceName/projects/$projectId/optimizations");
    expect(result.search).toEqual({ size: 10 });
  });

  it("falls back to /home for workspace-level routes (no /projects/ in pathname)", () => {
    const result = resolveProjectSwitchTarget(
      [match("/"), match("/my-ws"), match("/my-ws/dashboards")],
      { anything: "x" },
      WS,
      NEW_PROJECT,
    );
    expect(result).toEqual({
      to: "/$workspaceName/projects/$projectId/home",
      params: { workspaceName: WS, projectId: NEW_PROJECT },
    });
    expect(result.search).toBeUndefined();
  });

  it("falls back to /home when on /projects/<id> with no sub-path", () => {
    const result = resolveProjectSwitchTarget(
      projectChain,
      {},
      WS,
      NEW_PROJECT,
    );
    expect(result.to).toBe("/$workspaceName/projects/$projectId/home");
    expect(result.search).toBeUndefined();
  });

  it("falls back to /home for unknown section (e.g. /traces redirect)", () => {
    const result = resolveProjectSwitchTarget(
      [...projectChain, match(`${CUR}/traces`)],
      {},
      WS,
      NEW_PROJECT,
    );
    expect(result.to).toBe("/$workspaceName/projects/$projectId/home");
    expect(result.search).toBeUndefined();
  });

  it("stays on /agent-playground (empty allowlist) with empty search", () => {
    const result = resolveProjectSwitchTarget(
      [...projectChain, match(`${CUR}/agent-playground`)],
      { anything: "x" },
      WS,
      NEW_PROJECT,
    );
    expect(result).toEqual({
      to: "/$workspaceName/projects/$projectId/agent-playground",
      params: { workspaceName: WS, projectId: NEW_PROJECT },
      search: {},
    });
  });

  it("stays on /home with empty search (search dropped)", () => {
    const result = resolveProjectSwitchTarget(
      [...projectChain, match(`${CUR}/home`)],
      { foo: "bar" },
      WS,
      NEW_PROJECT,
    );
    expect(result.to).toBe("/$workspaceName/projects/$projectId/home");
    expect(result.search).toEqual({});
  });
});
