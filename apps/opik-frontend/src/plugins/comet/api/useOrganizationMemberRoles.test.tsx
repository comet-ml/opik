import { ReactNode } from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import useOrganizationMemberRoles, {
  ORGANIZATION_MEMBER_ROLES_QUERY_KEY,
} from "./useOrganizationMemberRoles";
import useOrganizationAdmins from "./useOrganizationAdmins";
import { ORGANIZATION_ROLE_TYPE } from "@/plugins/comet/types";

const mockGet = vi.fn();
const mockPost = vi.fn();

vi.mock("../api", () => ({
  default: {
    get: (...args: unknown[]) => mockGet(...args),
    post: (...args: unknown[]) => mockPost(...args),
  },
}));

let queryClient: QueryClient;

const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
);

beforeEach(() => {
  queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  vi.clearAllMocks();
});

describe("useOrganizationMemberRoles", () => {
  it("asks only about the names it was given", async () => {
    mockPost.mockResolvedValue({
      data: { roles: { alice: ORGANIZATION_ROLE_TYPE.admin } },
    });

    const { result } = renderHook(
      () =>
        useOrganizationMemberRoles({
          organizationId: "org-1",
          userNames: ["bob", "alice"],
        }),
      { wrapper },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(mockPost).toHaveBeenCalledTimes(1);
    expect(mockPost.mock.calls[0][0]).toBe(
      "/organizations/org-1/members/roles",
    );
    expect(mockPost.mock.calls[0][1]).toEqual({
      userNames: ["alice", "bob"],
    });
    expect(result.current.data).toEqual({
      alice: ORGANIZATION_ROLE_TYPE.admin,
    });
  });

  it("chunks longer lists instead of truncating them, and answers for every name", async () => {
    const userNames = Array.from(
      { length: 501 },
      (_, i) => `user-${String(i).padStart(4, "0")}`,
    );

    mockPost.mockImplementation((_url, body) => ({
      data: {
        roles: Object.fromEntries(
          (body as { userNames: string[] }).userNames.map((name) => [
            name,
            ORGANIZATION_ROLE_TYPE.member,
          ]),
        ),
      },
    }));

    const { result } = renderHook(
      () => useOrganizationMemberRoles({ organizationId: "org-1", userNames }),
      { wrapper },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(mockPost).toHaveBeenCalledTimes(2);
    expect(mockPost.mock.calls[0][1]).toEqual({
      userNames: userNames.slice(0, 500),
    });
    expect(mockPost.mock.calls[1][1]).toEqual({
      userNames: userNames.slice(500),
    });
    expect(Object.keys(result.current.data ?? {})).toHaveLength(501);
  });

  it("treats the same members in a different order as the same query", async () => {
    mockPost.mockResolvedValue({ data: { roles: {} } });

    renderHook(
      () =>
        useOrganizationMemberRoles({
          organizationId: "org-1",
          userNames: ["alice", "bob"],
        }),
      { wrapper },
    );
    await waitFor(() => expect(mockPost).toHaveBeenCalledTimes(1));

    renderHook(
      () =>
        useOrganizationMemberRoles({
          organizationId: "org-1",
          userNames: ["bob", "alice"],
        }),
      { wrapper },
    );

    expect(
      queryClient
        .getQueryCache()
        .findAll({ queryKey: [ORGANIZATION_MEMBER_ROLES_QUERY_KEY] }),
    ).toHaveLength(1);
    expect(mockPost.mock.calls.map((call) => call[1])).toEqual([
      { userNames: ["alice", "bob"] },
      { userNames: ["alice", "bob"] },
    ]);
  });

  it("does not confuse two name sets that would join to the same string", async () => {
    // ["a", "b"] and ["a,b"] are different sets of members, but a comma-joined key renders both
    // as "a,b" — the second would then be served the first one's roles.
    mockPost.mockResolvedValue({ data: { roles: {} } });

    renderHook(
      () =>
        useOrganizationMemberRoles({
          organizationId: "org-1",
          userNames: ["a", "b"],
        }),
      { wrapper },
    );
    await waitFor(() => expect(mockPost).toHaveBeenCalledTimes(1));

    renderHook(
      () =>
        useOrganizationMemberRoles({
          organizationId: "org-1",
          userNames: ["a,b"],
        }),
      { wrapper },
    );
    await waitFor(() => expect(mockPost).toHaveBeenCalledTimes(2));

    expect(
      queryClient
        .getQueryCache()
        .findAll({ queryKey: [ORGANIZATION_MEMBER_ROLES_QUERY_KEY] }),
    ).toHaveLength(2);
    expect(mockPost.mock.calls.map((call) => call[1])).toEqual([
      { userNames: ["a", "b"] },
      { userNames: ["a,b"] },
    ]);
  });
});

describe("useOrganizationAdmins", () => {
  it("asks the server for one page of admins rather than filtering the organization", async () => {
    mockGet.mockResolvedValue({
      data: {
        data: [
          {
            userName: "alice",
            email: "alice@acme.com",
            role: ORGANIZATION_ROLE_TYPE.admin,
          },
        ],
        total: 12,
      },
    });

    const { result } = renderHook(
      () => useOrganizationAdmins({ organizationId: "org-1", limit: 3 }),
      { wrapper },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(mockGet.mock.calls[0][0]).toBe("/organizations/org-1/members/paged");
    expect(mockGet.mock.calls[0][1]).toMatchObject({
      params: { role: ORGANIZATION_ROLE_TYPE.admin, pageSize: 3 },
    });
    expect(result.current.data).toHaveLength(1);
  });
});
