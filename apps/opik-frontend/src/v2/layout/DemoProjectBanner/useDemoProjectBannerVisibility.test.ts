import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";

import { DEMO_PROJECT_NAME } from "@/constants/shared";
import { AGENT_ONBOARDING_STEPS } from "@/v2/pages/GetStartedPage/AgentOnboarding/AgentOnboardingContext";
import {
  useDemoProjectBannerVisibility,
  useIsDemoProjectById,
} from "./useDemoProjectBannerVisibility";

// ── mutable state the mock factories read ──────────────────────────────────
// Hoisted, because vi.mock factories are lifted above ordinary declarations.
const { storage } = vi.hoisted(() => ({
  storage: {} as Record<string, unknown>,
}));
let mockActiveProjectId: string | null = null;
let mockRouteProjectId: string | undefined;
let mockProjectsById: Record<string, { name: string }> = {};
let mockProjectPending = false;
let mockVariant: string | null | undefined;
// ───────────────────────────────────────────────────────────────────────────

vi.mock("@/store/AppStore", () => ({
  useActiveProjectId: () => mockActiveProjectId,
  useActiveWorkspaceName: () => "my-workspace",
}));

vi.mock("@tanstack/react-router", () => ({
  useParams: ({
    select,
  }: {
    select: (p: Record<string, unknown>) => unknown;
  }) => select({ projectId: mockRouteProjectId }),
}));

vi.mock("@/api/projects/useProjectById", () => ({
  default: ({ projectId }: { projectId?: string }) => ({
    // A pending query has no data yet, which is why "not a demo project" and
    // "not known yet" have to be told apart.
    data:
      projectId && !mockProjectPending
        ? mockProjectsById[projectId]
        : undefined,
    isPending: mockProjectPending,
  }),
}));

vi.mock("use-local-storage-state", async () => ({
  default: (
    await import("@/testing/localStorageStateMock")
  ).createLocalStorageStateMock(storage),
}));

vi.mock("posthog-js/react", () => ({
  useFeatureFlagVariantKey: () => mockVariant,
}));

const DEMO_ID = "demo-project-id";
const OWN_ID = "own-project-id";
const ONBOARDING_KEY = "agent-onboarding-my-workspace";

beforeEach(() => {
  for (const key of Object.keys(storage)) delete storage[key];
  mockActiveProjectId = OWN_ID;
  mockRouteProjectId = undefined;
  mockProjectsById = {
    [DEMO_ID]: { name: DEMO_PROJECT_NAME },
    [OWN_ID]: { name: "my-agent" },
  };
  mockProjectPending = false;
  // The onboarding flow variant resolves to "manual" by default, so tests that
  // care about the non-manual branch opt into it explicitly.
  mockVariant = undefined;
});

const visibility = () =>
  renderHook(() => useDemoProjectBannerVisibility()).result.current;

const onboardingAt = (step: string) => {
  storage[ONBOARDING_KEY] = { step, agentName: "my-agent" };
};

describe("useDemoProjectBannerVisibility", () => {
  describe("isBannerVisible", () => {
    // OPIK-6027 asked for the banner "while browsing the demo project", so the
    // page decides, not the sticky active project.
    it("is true on a demo project's page while onboarding is running", () => {
      mockRouteProjectId = DEMO_ID;
      mockVariant = "ai-assisted";
      onboardingAt(AGENT_ONBOARDING_STEPS.CONNECT_AGENT);

      expect(visibility().isBannerVisible).toBe(true);
    });

    it("is true on a demo project's page under the manual flow", () => {
      mockRouteProjectId = DEMO_ID;
      mockVariant = "manual";
      onboardingAt(AGENT_ONBOARDING_STEPS.DONE);

      expect(visibility().isBannerVisible).toBe(true);
    });

    it("is false on a demo project's page once onboarding is done outside the manual flow", () => {
      mockRouteProjectId = DEMO_ID;
      mockVariant = "ai-assisted";
      onboardingAt(AGENT_ONBOARDING_STEPS.DONE);

      expect(visibility().isBannerVisible).toBe(false);
    });

    // The behaviour this branch changes: the demo project stays the active one
    // long after you leave it, and the bar used to follow it everywhere.
    it("is false on a workspace-level page even while the demo project is active", () => {
      mockActiveProjectId = DEMO_ID;
      mockRouteProjectId = undefined;
      onboardingAt(AGENT_ONBOARDING_STEPS.CONNECT_AGENT);

      expect(visibility().isBannerVisible).toBe(false);
    });

    it("is false on another project's page while the demo project is active", () => {
      mockActiveProjectId = DEMO_ID;
      mockRouteProjectId = OWN_ID;
      onboardingAt(AGENT_ONBOARDING_STEPS.CONNECT_AGENT);

      expect(visibility().isBannerVisible).toBe(false);
    });
  });

  describe("isDemoProjectActive", () => {
    // Onboarding auto-completion keys off this: it watches the user's own
    // project for traces and must keep working wherever the user navigated to.
    it("follows the sticky active project, not the page", () => {
      mockActiveProjectId = DEMO_ID;
      mockRouteProjectId = OWN_ID;

      expect(visibility().isDemoProjectActive).toBe(true);
    });

    it("is false when the active project is the user's own", () => {
      mockActiveProjectId = OWN_ID;

      expect(visibility().isDemoProjectActive).toBe(false);
    });
  });

  describe("isSettled", () => {
    it("is settled on a page with no project to resolve", () => {
      mockRouteProjectId = undefined;
      mockProjectPending = true;

      expect(visibility().isSettled).toBe(true);
    });

    it("is unsettled while the page's project is still loading", () => {
      mockRouteProjectId = DEMO_ID;
      mockProjectPending = true;

      const { isSettled, isOnDemoProjectPage } = visibility();

      expect(isSettled).toBe(false);
      expect(isOnDemoProjectPage).toBe(false);
    });
  });
});

describe("useIsDemoProjectById", () => {
  const verdict = (projectId?: string) =>
    renderHook(() => useIsDemoProjectById(projectId)).result.current;

  it("is true for the seeded demo project's id", () => {
    expect(verdict(DEMO_ID)).toEqual({ isDemoProject: true, isSettled: true });
  });

  it("is false for another project's id", () => {
    expect(verdict(OWN_ID)).toEqual({ isDemoProject: false, isSettled: true });
  });

  it("counts having no project as settled", () => {
    mockProjectPending = true;
    expect(verdict(undefined)).toEqual({
      isDemoProject: false,
      isSettled: true,
    });
  });
});
