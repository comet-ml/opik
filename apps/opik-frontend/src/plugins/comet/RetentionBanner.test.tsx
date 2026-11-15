import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { ORGANIZATION_ROLE_TYPE } from "@/plugins/comet/types";
import { QUOTA_TYPE } from "@/plugins/comet/types/quotas";
import { TooltipProvider } from "@/ui/tooltip";
import RetentionBanner from "./RetentionBanner";

const ORG_ID = "org-1";
const WORKSPACE = "my-workspace";
const SPAN_LIMIT = 25000;

const hooks = vi.hoisted(() => ({
  useActiveWorkspaceName: vi.fn(),
  useUser: vi.fn(),
  useWorkspaceQuotas: vi.fn(),
  useAllWorkspaces: vi.fn(),
  useOrganizations: vi.fn(),
  useOrganizationMembers: vi.fn(),
}));

vi.mock("@/store/AppStore", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/store/AppStore")>();
  return { ...actual, useActiveWorkspaceName: hooks.useActiveWorkspaceName };
});
vi.mock("@/plugins/comet/useUser", () => ({ default: hooks.useUser }));
vi.mock("@/plugins/comet/api/useWorkspaceQuotas", () => ({
  default: hooks.useWorkspaceQuotas,
}));
vi.mock("@/plugins/comet/useAllWorkspaces", () => ({
  default: hooks.useAllWorkspaces,
}));
vi.mock("@/plugins/comet/useOrganizations", () => ({
  default: hooks.useOrganizations,
}));
vi.mock("@/plugins/comet/api/useOrganizationMembers", () => ({
  default: hooks.useOrganizationMembers,
}));

const member = (userName: string, role: ORGANIZATION_ROLE_TYPE) => ({
  userName,
  email: `${userName}@example.com`,
  role,
});

const givenQuotas = (used: number, trialExpired = false) => {
  hooks.useWorkspaceQuotas.mockReturnValue({
    data: {
      quotas: [{ type: QUOTA_TYPE.OPIK_SPAN_COUNT, limit: SPAN_LIMIT, used }],
      trialExpired,
    },
  });
};

const givenRole = (role: ORGANIZATION_ROLE_TYPE) => {
  hooks.useOrganizations.mockReturnValue({ data: [{ id: ORG_ID, role }] });
};

const givenMembers = (...members: ReturnType<typeof member>[]) => {
  hooks.useOrganizationMembers.mockReturnValue({ data: members });
};

const renderBanner = () =>
  render(<RetentionBanner onChangeHeight={vi.fn()} />, {
    wrapper: TooltipProvider,
  });

describe("RetentionBanner", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    hooks.useActiveWorkspaceName.mockReturnValue(WORKSPACE);
    hooks.useUser.mockReturnValue({ data: { loggedIn: true } });
    hooks.useAllWorkspaces.mockReturnValue({
      data: [{ workspaceName: WORKSPACE, organizationId: ORG_ID }],
    });
    givenRole(ORGANIZATION_ROLE_TYPE.admin);
    givenMembers(member("owner", ORGANIZATION_ROLE_TYPE.admin));
  });

  describe("expired trial", () => {
    beforeEach(() => givenQuotas(0, true));

    it("offers an admin a single payment link that opens the plan picker", () => {
      renderBanner();

      const links = screen.getAllByRole("link");
      expect(links).toHaveLength(1);
      expect(links[0]).toHaveTextContent("Update your payment details");
      const href = links[0].getAttribute("href");
      expect(href).toContain(`organizations/${ORG_ID}/billing`);
      expect(href).toContain("initialOpenUpgradeCard=true");
      expect(href).toContain("redirectFromTrialModal=true");
    });

    it("asks a non-admin to contact the admins, without billing links", () => {
      givenRole(ORGANIZATION_ROLE_TYPE.member);
      givenMembers(
        member("owner", ORGANIZATION_ROLE_TYPE.admin),
        member("colleague", ORGANIZATION_ROLE_TYPE.member),
      );

      renderBanner();

      expect(screen.getByRole("region")).toHaveTextContent(
        "Ask your organization admin (owner) to update the payment details.",
      );
      expect(screen.queryByRole("link")).toBeNull();
    });

    it("lists two admins without a trailing ellipsis", () => {
      givenRole(ORGANIZATION_ROLE_TYPE.member);
      givenMembers(
        member("owner", ORGANIZATION_ROLE_TYPE.admin),
        member("co-owner", ORGANIZATION_ROLE_TYPE.admin),
      );

      renderBanner();

      expect(screen.getByRole("region")).toHaveTextContent(
        "Ask your organization admins (owner, co-owner) to update the payment details.",
      );
    });

    it("waits for the organization before rendering", () => {
      hooks.useOrganizations.mockReturnValue({ data: undefined });

      const { container } = renderBanner();

      expect(container).toBeEmptyDOMElement();
    });

    it("waits for the member list before showing a non-admin the admin names", () => {
      givenRole(ORGANIZATION_ROLE_TYPE.member);
      hooks.useOrganizationMembers.mockReturnValue({ data: undefined });

      const { container } = renderBanner();

      expect(container).toBeEmptyDOMElement();
    });

    it("still tells a non-admin to ask their admins when the member list fails to load", () => {
      givenRole(ORGANIZATION_ROLE_TYPE.member);
      hooks.useOrganizationMembers.mockReturnValue({
        data: undefined,
        isError: true,
      });

      renderBanner();

      expect(screen.getByRole("region")).toHaveTextContent(
        "Ask your organization admins to update the payment details.",
      );
    });

    it("shows an admin the payment link without waiting for the member list", () => {
      hooks.useOrganizationMembers.mockReturnValue({ data: undefined });

      renderBanner();

      expect(
        screen.getByRole("link", { name: "Update your payment details" }),
      ).toBeVisible();
    });

    it("cannot be dismissed", () => {
      renderBanner();

      expect(screen.queryByRole("button", { name: "Dismiss" })).toBeNull();
    });
  });

  it("stays hidden for a Free organization below 80% of its spans", () => {
    givenQuotas(SPAN_LIMIT * 0.5);

    const { container } = renderBanner();

    expect(container).toBeEmptyDOMElement();
  });

  it("shows the 80% warning to a Free organization above 80% of its spans", () => {
    givenQuotas(SPAN_LIMIT * 0.9);

    renderBanner();

    expect(
      screen.getByText(
        `You're at 80% of your ${SPAN_LIMIT} free spans. Upgrade to ensure uninterrupted monitoring`,
      ),
    ).toBeVisible();
  });

  it("lets a Free organization dismiss the 80% warning", () => {
    givenQuotas(SPAN_LIMIT * 0.9);

    renderBanner();

    expect(screen.getByRole("button", { name: "Dismiss" })).toBeVisible();
  });
});
