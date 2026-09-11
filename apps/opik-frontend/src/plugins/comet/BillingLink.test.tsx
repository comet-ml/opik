import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { ORGANIZATION_ROLE_TYPE } from "@/plugins/comet/types";
import BillingLink from "./BillingLink";

const ORG_ID = "org-1";
const WORKSPACE = "my-workspace";

const hooks = vi.hoisted(() => ({
  useActiveWorkspaceName: vi.fn(),
  useUser: vi.fn(),
  useAllWorkspaces: vi.fn(),
  useOrganizations: vi.fn(),
}));

vi.mock("@/store/AppStore", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/store/AppStore")>();
  return { ...actual, useActiveWorkspaceName: hooks.useActiveWorkspaceName };
});
vi.mock("@/plugins/comet/useUser", () => ({ default: hooks.useUser }));
vi.mock("@/plugins/comet/useAllWorkspaces", () => ({
  default: hooks.useAllWorkspaces,
}));
vi.mock("@/plugins/comet/useOrganizations", () => ({
  default: hooks.useOrganizations,
}));

const givenRole = (role?: ORGANIZATION_ROLE_TYPE) => {
  hooks.useOrganizations.mockReturnValue({
    data: role ? [{ id: ORG_ID, role }] : undefined,
  });
};

describe("BillingLink", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    hooks.useActiveWorkspaceName.mockReturnValue(WORKSPACE);
    hooks.useUser.mockReturnValue({ data: { loggedIn: true } });
    hooks.useAllWorkspaces.mockReturnValue({
      data: [{ workspaceName: WORKSPACE, organizationId: ORG_ID }],
    });
    givenRole(ORGANIZATION_ROLE_TYPE.admin);
  });

  it("links an organization admin to the Ollie credits page", () => {
    render(<BillingLink />);

    const link = screen.getByRole("link", { name: "View billing" });
    expect(link).toHaveAttribute(
      "href",
      expect.stringContaining(`organizations/${ORG_ID}/ollie-credits`),
    );
  });

  it("renders the action variant with a custom label", () => {
    render(<BillingLink label="Add credits" variant="action" />);

    expect(screen.getByRole("link", { name: "Add credits" })).toBeVisible();
  });

  // Ollie credits live in the Admin Dashboard, which non-admins cannot open — offering the link
  // sends them to a page that bounces them back to Opik with no explanation.
  it.each([
    ["a member", ORGANIZATION_ROLE_TYPE.member],
    ["a view-only member", ORGANIZATION_ROLE_TYPE.viewOnly],
    ["an LLM-only user", ORGANIZATION_ROLE_TYPE.opik],
  ])("renders nothing for %s", (_label, role) => {
    givenRole(role);

    const { container } = render(<BillingLink />);

    expect(container).toBeEmptyDOMElement();
  });

  // Fails closed: the organizations query resolves after first paint, and briefly showing an
  // admin-only link to everyone is worse than showing it a moment late.
  it("renders nothing while organizations are still loading", () => {
    givenRole(undefined);

    const { container } = render(<BillingLink />);

    expect(container).toBeEmptyDOMElement();
  });

  it("renders nothing when the organization cannot be matched to the workspace", () => {
    hooks.useOrganizations.mockReturnValue({
      data: [{ id: "some-other-org", role: ORGANIZATION_ROLE_TYPE.admin }],
    });

    const { container } = render(<BillingLink />);

    expect(container).toBeEmptyDOMElement();
  });

  it("renders nothing when the workspace has no organization", () => {
    hooks.useAllWorkspaces.mockReturnValue({
      data: [{ workspaceName: WORKSPACE, organizationId: undefined }],
    });

    const { container } = render(<BillingLink />);

    expect(container).toBeEmptyDOMElement();
  });
});
