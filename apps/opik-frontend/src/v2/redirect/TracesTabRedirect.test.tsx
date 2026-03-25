import { describe, it, expect, vi, beforeEach } from "vitest";
import { render } from "@testing-library/react";
import TracesTabRedirect from "./TracesTabRedirect";

const mockNavigate = vi.fn();
let mockSearch: Record<string, string> = {};

vi.mock("@tanstack/react-router", () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({ projectId: "proj-123" }),
  useRouterState: ({ select }: { select: (s: unknown) => unknown }) =>
    select({ location: { search: mockSearch } }),
}));

vi.mock("@/store/AppStore", () => ({
  useActiveWorkspaceName: () => "default",
}));

const projectRoute = (suffix: string) => ({
  to: `/$workspaceName/projects/$projectId${suffix}`,
  params: { workspaceName: "default", projectId: "proj-123" },
  replace: true,
});

describe("TracesTabRedirect", () => {
  beforeEach(() => {
    mockNavigate.mockClear();
    mockSearch = {};
  });

  // --- ?tab= redirects ---

  it("redirects ?tab=annotation-queues to /annotation-queues", () => {
    mockSearch = { tab: "annotation-queues" };
    render(<TracesTabRedirect />);
    expect(mockNavigate).toHaveBeenCalledWith(
      projectRoute("/annotation-queues"),
    );
  });

  it("redirects ?tab=rules to /online-evaluation", () => {
    mockSearch = { tab: "rules" };
    render(<TracesTabRedirect />);
    expect(mockNavigate).toHaveBeenCalledWith(
      projectRoute("/online-evaluation"),
    );
  });

  it("redirects ?tab=configuration to /agent-configuration", () => {
    mockSearch = { tab: "configuration" };
    render(<TracesTabRedirect />);
    expect(mockNavigate).toHaveBeenCalledWith(
      projectRoute("/agent-configuration"),
    );
  });

  it("redirects ?tab=insights to /insights", () => {
    mockSearch = { tab: "insights" };
    render(<TracesTabRedirect />);
    expect(mockNavigate).toHaveBeenCalledWith(projectRoute("/insights"));
  });

  it("redirects ?tab=metrics to /insights", () => {
    mockSearch = { tab: "metrics" };
    render(<TracesTabRedirect />);
    expect(mockNavigate).toHaveBeenCalledWith(projectRoute("/insights"));
  });

  it("redirects ?tab=logs to /logs", () => {
    mockSearch = { tab: "logs" };
    render(<TracesTabRedirect />);
    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/$workspaceName/projects/$projectId/logs",
      params: { workspaceName: "default", projectId: "proj-123" },
      search: undefined,
      replace: true,
    });
  });

  it("redirects ?tab=logs&logsType=threads to /logs with logsType", () => {
    mockSearch = { tab: "logs", logsType: "threads" };
    render(<TracesTabRedirect />);
    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/$workspaceName/projects/$projectId/logs",
      params: { workspaceName: "default", projectId: "proj-123" },
      search: { logsType: "threads" },
      replace: true,
    });
  });

  // --- Legacy ?type= redirects ---

  it("redirects ?type=metrics to /insights", () => {
    mockSearch = { type: "metrics" };
    render(<TracesTabRedirect />);
    expect(mockNavigate).toHaveBeenCalledWith(projectRoute("/insights"));
  });

  it("redirects ?type=traces to /logs with logsType=traces", () => {
    mockSearch = { type: "traces" };
    render(<TracesTabRedirect />);
    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/$workspaceName/projects/$projectId/logs",
      params: { workspaceName: "default", projectId: "proj-123" },
      search: { logsType: "traces" },
      replace: true,
    });
  });

  it("redirects ?type=threads to /logs with logsType=threads", () => {
    mockSearch = { type: "threads" };
    render(<TracesTabRedirect />);
    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/$workspaceName/projects/$projectId/logs",
      params: { workspaceName: "default", projectId: "proj-123" },
      search: { logsType: "threads" },
      replace: true,
    });
  });

  it("redirects ?type=spans to /logs with logsType=spans", () => {
    mockSearch = { type: "spans" };
    render(<TracesTabRedirect />);
    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/$workspaceName/projects/$projectId/logs",
      params: { workspaceName: "default", projectId: "proj-123" },
      search: { logsType: "spans" },
      replace: true,
    });
  });

  it("redirects ?type=rules to /online-evaluation", () => {
    mockSearch = { type: "rules" };
    render(<TracesTabRedirect />);
    expect(mockNavigate).toHaveBeenCalledWith(
      projectRoute("/online-evaluation"),
    );
  });

  it("redirects ?type=annotation-queues to /annotation-queues", () => {
    mockSearch = { type: "annotation-queues" };
    render(<TracesTabRedirect />);
    expect(mockNavigate).toHaveBeenCalledWith(
      projectRoute("/annotation-queues"),
    );
  });

  it("redirects ?type=configuration to /agent-configuration", () => {
    mockSearch = { type: "configuration" };
    render(<TracesTabRedirect />);
    expect(mockNavigate).toHaveBeenCalledWith(
      projectRoute("/agent-configuration"),
    );
  });

  // --- Legacy ?view= redirect ---

  it("redirects ?view=dashboards to /insights", () => {
    mockSearch = { view: "dashboards" };
    render(<TracesTabRedirect />);
    expect(mockNavigate).toHaveBeenCalledWith(projectRoute("/insights"));
  });

  // --- Default behavior ---

  it("redirects to /logs with no params", () => {
    mockSearch = {};
    render(<TracesTabRedirect />);
    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/$workspaceName/projects/$projectId/logs",
      params: { workspaceName: "default", projectId: "proj-123" },
      search: undefined,
      replace: true,
    });
  });

  // --- Mixed params (real-world case) ---

  it("redirects with ?tab=annotation-queues and forwards remaining params", () => {
    mockSearch = {
      traces_filters: "[]",
      time_range: "past30days",
      tab: "annotation-queues",
      queues_filters: "[]",
    };
    render(<TracesTabRedirect />);
    expect(mockNavigate).toHaveBeenCalledWith({
      ...projectRoute("/annotation-queues"),
      search: {
        traces_filters: "[]",
        time_range: "past30days",
        queues_filters: "[]",
      },
    });
  });

  // --- SDK trace redirect (OPIK-4115) ---

  it("preserves trace param from SDK redirect URL", () => {
    mockSearch = {
      tab: "logs",
      logsType: "traces",
      trace: "trace-abc-123",
    };
    render(<TracesTabRedirect />);
    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/$workspaceName/projects/$projectId/logs",
      params: { workspaceName: "default", projectId: "proj-123" },
      search: { logsType: "traces", trace: "trace-abc-123" },
      replace: true,
    });
  });

  it("preserves all filter params when redirecting to /logs", () => {
    mockSearch = {
      tab: "logs",
      logsType: "traces",
      traces_filters: "[]",
      time_range: "past30days",
      size: "100",
      height: "small",
    };
    render(<TracesTabRedirect />);
    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/$workspaceName/projects/$projectId/logs",
      params: { workspaceName: "default", projectId: "proj-123" },
      search: {
        logsType: "traces",
        traces_filters: "[]",
        time_range: "past30days",
        size: "100",
        height: "small",
      },
      replace: true,
    });
  });
});
