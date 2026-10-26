import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { ORGANIZATION_ROLE_TYPE, Workspace } from "./types";
import WorkspacePreloader from "./WorkspacePreloader";

const ORG_ID = "org-1";
const SPEND_WORKSPACE = `__ai_spend_${ORG_ID}__`;

const route = vi.hoisted(() => ({
  workspaceName: undefined as string | undefined,
  pathname: "/",
}));

const hooks = vi.hoisted(() => ({
  useUser: vi.fn(),
  useAllWorkspaces: vi.fn(),
  useOrganizations: vi.fn(),
}));

vi.mock("@tanstack/react-router", () => ({
  Navigate: ({ params }: { params: { workspaceName: string } }) =>
    `navigate:${params.workspaceName}`,
  Link: ({ children }: { children: ReactNode }) => children,
  useLocation: () => ({ pathname: route.pathname }),
  useMatchRoute: () => () => route.pathname === "/",
  useParams: ({
    select,
  }: {
    select: (params: Record<string, string | undefined>) => unknown;
  }) => select({ workspaceName: route.workspaceName }),
}));
vi.mock("posthog-js/react", () => ({ usePostHog: () => undefined }));
vi.mock("./analytics/useSegment", () => ({ default: vi.fn() }));
vi.mock("./analytics/reo", () => ({ identifyReoUser: vi.fn() }));
vi.mock("./useUser", () => ({ default: hooks.useUser }));
vi.mock("./useAllWorkspaces", () => ({ default: hooks.useAllWorkspaces }));
vi.mock("./useOrganizations", () => ({ default: hooks.useOrganizations }));

const workspace = (workspaceName: string, isDefault = false): Workspace => ({
  workspaceId: workspaceName,
  workspaceName,
  organizationId: ORG_ID,
  default: isDefault,
});

const givenWorkspaces = (workspaces: Workspace[]) =>
  hooks.useAllWorkspaces.mockReturnValue({ data: workspaces });

describe("WorkspacePreloader", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    route.workspaceName = undefined;
    route.pathname = "/";
    hooks.useUser.mockReturnValue({
      isLoading: false,
      data: {
        loggedIn: true,
        apiKeys: ["key"],
        userName: "admin",
        email: "admin@example.com",
        gitHub: false,
        defaultWorkspace: "admin",
        suspended: false,
      },
    });
    hooks.useOrganizations.mockReturnValue({
      data: [{ id: ORG_ID, role: ORGANIZATION_ROLE_TYPE.admin }],
    });
  });

  // Org admins get the AI Spend workspace in their list, often first.
  it("lands an admin with no default workspace on a regular workspace", () => {
    givenWorkspaces([workspace(SPEND_WORKSPACE), workspace("team-a")]);

    render(<WorkspacePreloader>app</WorkspacePreloader>);

    expect(screen.getByText("navigate:team-a")).toBeInTheDocument();
  });

  it("lands on the default workspace when one is set", () => {
    givenWorkspaces([
      workspace(SPEND_WORKSPACE),
      workspace("team-a"),
      workspace("team-b", true),
    ]);

    render(<WorkspacePreloader>app</WorkspacePreloader>);

    expect(screen.getByText("navigate:team-b")).toBeInTheDocument();
  });

  it("sends a user who must verify their email to Comet's verification page", () => {
    hooks.useUser.mockReturnValue({
      isLoading: false,
      data: {
        loggedIn: true,
        emailVerificationRequired: true,
        apiKeys: [],
        userName: "new-user",
        email: "new-user@example.com",
        gitHub: false,
        defaultWorkspace: "new-user",
        suspended: false,
      },
    });
    givenWorkspaces([workspace("new-user", true)]);
    const originalLocation = window.location;
    Object.defineProperty(window, "location", {
      value: { href: "" },
      writable: true,
    });

    try {
      render(<WorkspacePreloader>app</WorkspacePreloader>);

      expect(window.location.href).toContain("email-verification");
      expect(screen.queryByText("app")).not.toBeInTheDocument();
    } finally {
      Object.defineProperty(window, "location", {
        value: originalLocation,
        writable: true,
      });
    }
  });

  it("still opens the AI Spend pages", () => {
    givenWorkspaces([workspace(SPEND_WORKSPACE), workspace("team-a")]);
    route.workspaceName = SPEND_WORKSPACE;
    route.pathname = `/${SPEND_WORKSPACE}/ai-spend`;

    render(<WorkspacePreloader>ai spend page</WorkspacePreloader>);

    expect(screen.getByText("ai spend page")).toBeInTheDocument();
  });
});
