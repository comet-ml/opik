import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";

import useWorkspaceSelectorData from "./useWorkspaceSelectorData";

const navigate = vi.fn();
const toast = vi.fn();
const recordVisit = vi.fn();
const fetchNextPage = vi.fn();
const postWorkspacePointRead = vi.fn();

let recentWorkspaces: Array<{
  workspaceId: string;
  workspaceName: string;
  organizationId: string;
  visitedAt: number;
}> = [];
let pages: Array<{ data: unknown[]; total: number }> = [];

vi.mock("@tanstack/react-router", () => ({
  useNavigate: () => navigate,
}));
vi.mock("@/ui/use-toast", () => ({ useToast: () => ({ toast }) }));
vi.mock("@/plugins/comet/useUser", () => ({
  default: () => ({ data: { loggedIn: true } }),
}));
vi.mock("@/plugins/comet/useOrganizations", () => ({
  default: () => ({
    data: [
      { id: "org-1", name: "Org One" },
      { id: "org-2", name: "Org Two" },
    ],
  }),
}));
vi.mock("@/plugins/comet/useCurrentOrganization", () => ({
  default: () => ({ id: "org-1", name: "Org One", role: "ADMIN" }),
}));
vi.mock("@/plugins/comet/useWorkspace", () => ({ default: () => undefined }));
vi.mock("@/store/AppStore", () => ({
  default: (selector: (s: { activeWorkspaceName: string }) => unknown) =>
    selector({ activeWorkspaceName: "ws-active" }),
}));
vi.mock("@/plugins/comet/useRecentWorkspaces", () => ({
  default: () => ({ recentWorkspaces, recordVisit }),
}));
vi.mock("@/plugins/comet/useOrganizationWorkspacesPage", () => ({
  default: () => ({
    data: { pages },
    isLoading: false,
    isFetchingNextPage: false,
    hasNextPage: false,
    fetchNextPage,
  }),
}));
vi.mock("@/plugins/comet/lib/workspacePointRead", () => ({
  postWorkspacePointRead: (...args: unknown[]) =>
    postWorkspacePointRead(...args),
}));

describe("useWorkspaceSelectorData", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    recentWorkspaces = [];
    pages = [];
  });

  it("scopes recents to the current organization and drops default / ai-spend", () => {
    recentWorkspaces = [
      { workspaceId: "1", workspaceName: "keep-me", organizationId: "org-1", visitedAt: 3 },
      { workspaceId: "2", workspaceName: "other-org", organizationId: "org-2", visitedAt: 2 },
      { workspaceId: "3", workspaceName: "default", organizationId: "org-1", visitedAt: 1 },
    ];

    const { result } = renderHook(() => useWorkspaceSelectorData());

    expect(result.current.recents.map((w) => w.workspaceName)).toEqual([
      "keep-me",
    ]);
  });

  it("flattens server pages into a single list", () => {
    pages = [
      { data: [{ workspaceId: "a", workspaceName: "a", organizationId: "org-1", default: false }], total: 2 },
      { data: [{ workspaceId: "b", workspaceName: "b", organizationId: "org-1", default: false }], total: 2 },
    ];

    const { result } = renderHook(() => useWorkspaceSelectorData());

    expect(result.current.serverWorkspaces.map((w) => w.workspaceId)).toEqual([
      "a",
      "b",
    ]);
    expect(result.current.total).toBe(2);
  });

  it("changes organization via the landing point read and navigates there", async () => {
    postWorkspacePointRead.mockResolvedValueOnce({
      workspaceId: "x",
      workspaceName: "landing-ws",
      organizationId: "org-2",
      default: true,
    });

    const { result } = renderHook(() => useWorkspaceSelectorData());
    await act(async () => {
      await result.current.handleChangeOrganization({
        id: "org-2",
        name: "Org Two",
      } as never);
    });

    expect(postWorkspacePointRead).toHaveBeenCalledWith(
      "/workspaces/retrieve-landing",
      { organizationId: "org-2" },
    );
    expect(navigate).toHaveBeenCalledWith({
      to: "/$workspaceName",
      params: { workspaceName: "landing-ws" },
    });
    expect(toast).not.toHaveBeenCalled();
  });

  it("toasts and does not navigate when the target organization has no workspaces", async () => {
    postWorkspacePointRead.mockResolvedValueOnce(null);

    const { result } = renderHook(() => useWorkspaceSelectorData());
    await act(async () => {
      await result.current.handleChangeOrganization({
        id: "org-2",
        name: "Org Two",
      } as never);
    });

    await waitFor(() => expect(toast).toHaveBeenCalledTimes(1));
    expect(navigate).not.toHaveBeenCalled();
  });
});
