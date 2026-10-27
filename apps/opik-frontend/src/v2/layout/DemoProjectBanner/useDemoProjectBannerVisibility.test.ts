import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";

import { DEMO_PROJECT_NAME } from "@/constants/shared";
import { AGENT_ONBOARDING_STEPS } from "@/v2/pages/GetStartedPage/AgentOnboarding/AgentOnboardingContext";
import { useDemoProjectBannerVisibility } from "./useDemoProjectBannerVisibility";

const { storage } = vi.hoisted(() => ({
  storage: {} as Record<string, unknown>,
}));
let mockActiveProjectId: string | null = null;
let mockRouteProjectId: string | undefined;
let mockVariant: string | undefined;

const DEMO_ID = "demo-project-id";
const OWN_ID = "own-project-id";
const NAMES: Record<string, string> = {
  [DEMO_ID]: DEMO_PROJECT_NAME,
  [OWN_ID]: "my-agent",
};

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
    data: projectId ? { name: NAMES[projectId] } : undefined,
    isPending: false,
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

beforeEach(() => {
  for (const key of Object.keys(storage)) delete storage[key];
  mockActiveProjectId = DEMO_ID;
  mockRouteProjectId = undefined;
  mockVariant = "ai-assisted";
  storage["agent-onboarding-my-workspace"] = {
    step: AGENT_ONBOARDING_STEPS.CONNECT_AGENT,
    agentName: "my-agent",
  };
});

const visibility = () =>
  renderHook(() => useDemoProjectBannerVisibility()).result.current;

describe("useDemoProjectBannerVisibility", () => {
  it("shows on the demo project's page while onboarding runs", () => {
    mockRouteProjectId = DEMO_ID;
    expect(visibility().isBannerVisible).toBe(true);
  });

  it("shows on the demo project's page under the manual flow", () => {
    mockRouteProjectId = DEMO_ID;
    mockVariant = "manual";
    storage["agent-onboarding-my-workspace"] = {
      step: AGENT_ONBOARDING_STEPS.DONE,
      agentName: "my-agent",
    };
    expect(visibility().isBannerVisible).toBe(true);
  });

  it("hides on the demo project's page once onboarding is done outside the manual flow", () => {
    mockRouteProjectId = DEMO_ID;
    storage["agent-onboarding-my-workspace"] = {
      step: AGENT_ONBOARDING_STEPS.DONE,
      agentName: "my-agent",
    };
    expect(visibility().isBannerVisible).toBe(false);
  });

  it("hides everywhere else, even while the demo project is the active one", () => {
    mockRouteProjectId = undefined;
    expect(visibility().isBannerVisible).toBe(false);

    mockRouteProjectId = OWN_ID;
    expect(visibility().isBannerVisible).toBe(false);
  });

  it("still reports the active demo project for onboarding auto-completion", () => {
    mockRouteProjectId = OWN_ID;
    expect(visibility().isDemoProjectActive).toBe(true);
  });
});
