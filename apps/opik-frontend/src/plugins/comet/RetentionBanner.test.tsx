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

    it("offers an admin both payment and downgrade, opening the plan picker", () => {
      renderBanner();

      for (const name of [
        "Update your payment details",
        "downgrade to the Free plan",
      ]) {
        const href = screen.getByRole("link", { name }).getAttribute("href");
        expect(href).toContain(`organizations/${ORG_ID}/billing`);
        expect(href).toContain("initialOpenUpgradeCard=true");
        expect(href).toContain("redirectFromTrialModal=true");
      }
    });

    // Billing doesn't offer Free to these organizations, so the link would lead nowhere.
    it("hides the downgrade link when the organization has other full members", () => {
      givenMembers(
        member("owner", ORGANIZATION_ROLE_TYPE.admin),
        member("colleague", ORGANIZATION_ROLE_TYPE.member),
      );

      renderBanner();

      expect(
        screen.getByRole("link", { name: "Update your payment details" }),
      ).toBeVisible();
      expect(
        screen.queryByRole("link", { name: "downgrade to the Free plan" }),
      ).toBeNull();
    });

    it("keeps the downgrade link when the other members are Opik-only", () => {
      givenMembers(
        member("owner", ORGANIZATION_ROLE_TYPE.admin),
        member("opik-user", ORGANIZATION_ROLE_TYPE.opik),
      );

      renderBanner();

      expect(
        screen.getByRole("link", { name: "downgrade to the Free plan" }),
      ).toBeVisible();
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

    it("waits for the organization members before rendering", () => {
      hooks.useOrganizationMembers.mockReturnValue({ data: undefined });

      const { container } = renderBanner();

      expect(container).toBeEmptyDOMElement();
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

  it("lets a Free organization dismiss the 80% warning", () => {
    givenQuotas(SPAN_LIMIT * 0.9);

    renderBanner();

    expect(screen.getByRole("button", { name: "Dismiss" })).toBeVisible();
  });
});
