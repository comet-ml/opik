import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";

import { DEMO_PROJECT_NAME } from "@/constants/shared";
import { AGENT_ONBOARDING_STEPS } from "@/v2/pages/GetStartedPage/AgentOnboarding/AgentOnboardingContext";
import {
  useDemoProjectBannerVisibility,
  useIsDemoProjectById,
} from "./useDemoProjectBannerVisibility";

// ── mutable state the mock factories read ──────────────────────────────────
let mockActiveProjectId: string | null = "project-1";
let mockProjectsById: Record<string, { name: string }> = {};
let mockOnboardingState: { step: string | null; agentName: string } | undefined;
let mockVariant: string | null | undefined;
// ───────────────────────────────────────────────────────────────────────────

vi.mock("@/store/AppStore", () => ({
  useActiveProjectId: () => mockActiveProjectId,
  useActiveWorkspaceName: () => "my-workspace",
}));

vi.mock("@/api/projects/useProjectById", () => ({
  default: ({ projectId }: { projectId?: string }) => ({
    data: projectId ? mockProjectsById[projectId] : undefined,
  }),
}));

vi.mock("use-local-storage-state", () => ({
  default: () => [mockOnboardingState, vi.fn()],
}));

vi.mock("posthog-js/react", () => ({
  useFeatureFlagVariantKey: () => mockVariant,
}));

const DEMO_ID = "demo-project-id";
const OWN_ID = "own-project-id";

beforeEach(() => {
  mockActiveProjectId = OWN_ID;
  mockProjectsById = {
    [DEMO_ID]: { name: DEMO_PROJECT_NAME },
    [OWN_ID]: { name: "my-agent" },
  };
  mockOnboardingState = undefined;
  // The onboarding flow variant resolves to "manual" by default, so tests that
  // care about the non-manual branch have to opt into it explicitly.
  mockVariant = undefined;
});

const visibility = () =>
  renderHook(() => useDemoProjectBannerVisibility()).result.current;

describe("useDemoProjectBannerVisibility", () => {
  describe("isDemoProject", () => {
    it("is true when the active project is the seeded demo project", () => {
      mockActiveProjectId = DEMO_ID;
      expect(visibility().isDemoProject).toBe(true);
    });

    it("is false when the active project is the user's own", () => {
      expect(visibility().isDemoProject).toBe(false);
    });

    it("is false while no project is active", () => {
      mockActiveProjectId = null;
      expect(visibility().isDemoProject).toBe(false);
    });

    it("is false while the active project has not loaded yet", () => {
      mockActiveProjectId = "not-in-cache";
      expect(visibility().isDemoProject).toBe(false);
    });
  });

  describe("isBannerVisible", () => {
    it("is true on the demo project while onboarding is still running", () => {
      mockActiveProjectId = DEMO_ID;
      mockVariant = "ai-assisted";
      mockOnboardingState = {
        step: AGENT_ONBOARDING_STEPS.CONNECT_AGENT,
        agentName: "my-agent",
      };
      expect(visibility().isBannerVisible).toBe(true);
    });

    it("is true on the demo project under the manual flow, onboarding aside", () => {
      mockActiveProjectId = DEMO_ID;
      mockVariant = "manual";
      mockOnboardingState = {
        step: AGENT_ONBOARDING_STEPS.DONE,
        agentName: "my-agent",
      };
      expect(visibility().isBannerVisible).toBe(true);
    });

    // The case that decides whether the MCP announcement may appear on a demo
    // project: onboarding finished and the flow is not manual, so the demo
    // banner takes itself off screen.
    it("is false on the demo project once onboarding is done outside the manual flow", () => {
      mockActiveProjectId = DEMO_ID;
      mockVariant = "ai-assisted";
      mockOnboardingState = {
        step: AGENT_ONBOARDING_STEPS.DONE,
        agentName: "my-agent",
      };
      expect(visibility().isBannerVisible).toBe(false);
    });

    it("is false on the user's own project even mid-onboarding", () => {
      mockVariant = "manual";
      mockOnboardingState = {
        step: AGENT_ONBOARDING_STEPS.CONNECT_AGENT,
        agentName: "my-agent",
      };
      expect(visibility().isBannerVisible).toBe(false);
    });
  });
});

describe("useIsDemoProjectById", () => {
  it("is true for the seeded demo project's id", () => {
    const { result } = renderHook(() => useIsDemoProjectById(DEMO_ID));
    expect(result.current).toBe(true);
  });

  it("is false for another project's id", () => {
    const { result } = renderHook(() => useIsDemoProjectById(OWN_ID));
    expect(result.current).toBe(false);
  });

  it("is false when no project id is given", () => {
    const { result } = renderHook(() => useIsDemoProjectById(undefined));
    expect(result.current).toBe(false);
  });
});
