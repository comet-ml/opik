import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import NewQuickstart from "./NewQuickstart";

// ── mutable state shared between tests and mock factories ──────────────────
const localStorageStore: Record<string, unknown> = {};
const localStorageSetters: Record<string, ReturnType<typeof vi.fn>> = {};

let mockVariant: string | null = "manual";
let mockMobileVariant: string | null | undefined = undefined;
let mockIsPhone = false;
let mockHasTraces = false;
let mockPollExpired = false;
let mockFirstTraceProjectId: string | null = null;
let mockProjectData: { id?: string } | undefined = undefined;
let mockProjectIsPending = false;
let mockDemoDataEnabled = true;
// ──────────────────────────────────────────────────────────────────────────

vi.mock("posthog-js/react", () => ({
  useFeatureFlagVariantKey: vi.fn((key: string) =>
    key === "guided-mobile-onboarding-flow" ? mockMobileVariant : mockVariant,
  ),
}));

vi.mock("@/hooks/useIsPhone", () => ({
  useIsPhone: vi.fn(() => ({
    isPhone: mockIsPhone,
    isPhonePortrait: mockIsPhone,
    isPhoneLandscape: false,
  })),
}));

vi.mock("./MobileOnboarding/MobileOnboarding", () => ({
  default: () => <div data-testid="mobile-onboarding" />,
}));

vi.mock("@/shared/Loader/Loader", () => ({
  default: () => <div data-testid="loader" />,
}));

vi.mock("use-local-storage-state", () => ({
  default: vi.fn((key: string, options?: { defaultValue?: unknown }) => {
    const value =
      key in localStorageStore
        ? localStorageStore[key]
        : options?.defaultValue ?? undefined;
    if (!localStorageSetters[key]) {
      localStorageSetters[key] = vi.fn((next: unknown) => {
        localStorageStore[key] = next;
      });
    }
    return [value, localStorageSetters[key]];
  }),
}));

const mockNavigate = vi.fn();

vi.mock("@tanstack/react-router", () => ({
  Navigate: vi.fn(
    ({ to, params }: { to: string; params?: Record<string, string> }) => (
      <div
        data-testid="navigate"
        data-to={to}
        data-params={params ? JSON.stringify(params) : undefined}
      />
    ),
  ),
  useNavigate: vi.fn(() => mockNavigate),
}));

vi.mock("@/api/projects/useFirstTraceReceived", () => ({
  default: vi.fn(() => ({
    hasTraces: mockHasTraces,
    firstTraceProjectId: mockFirstTraceProjectId,
    pollExpired: mockPollExpired,
  })),
}));

vi.mock("@/api/projects/useProjectByName", () => ({
  default: vi.fn(() => ({
    data: mockProjectData,
    isPending: mockProjectIsPending,
  })),
}));

vi.mock("@/store/AppStore", () => ({
  useActiveWorkspaceName: vi.fn(() => "test-ws"),
}));

vi.mock("./AgentOnboarding/AgentOnboardingOverlay", () => ({
  default: () => <div data-testid="agent-onboarding-overlay" />,
}));

vi.mock("./AgentOnboarding/DemoLoadingContent", () => ({
  default: ({
    onRetry,
    onComplete,
    retryLabel,
  }: {
    onRetry: () => void;
    onComplete: () => void;
    retryLabel?: string;
  }) => (
    <div data-testid="demo-loading-content">
      <button onClick={onRetry}>{retryLabel ?? "Retry"}</button>
      <button data-testid="complete-btn" onClick={onComplete}>
        Complete
      </button>
    </div>
  ),
}));

vi.mock(
  "@/shared/OnboardingIntegrationsPage/OnboardingIntegrationsPage",
  () => ({
    default: ({
      banner,
      onSkip,
    }: {
      banner?: React.ReactNode;
      onSkip?: () => void;
    }) => (
      <div data-testid="onboarding-integrations-page">
        {banner && <div data-testid="banner">{banner}</div>}
        {onSkip && (
          <button data-testid="skip-btn" onClick={onSkip}>
            Skip
          </button>
        )}
      </div>
    ),
  }),
);

vi.mock(
  "@/v2/pages-shared/onboarding/IntegrationExplorer/components/LoggedDataStatus",
  () => ({
    default: ({
      status,
      onExplore,
    }: {
      status: string;
      onExplore?: () => void;
    }) => (
      <div data-testid="logged-data-status" data-status={status}>
        {onExplore && (
          <button data-testid="explore-btn" onClick={onExplore}>
            Explore
          </button>
        )}
      </div>
    ),
  }),
);

vi.mock("@/v2/pages-shared/onboarding/IntegrationExplorer", () => ({
  IntegrationExplorer: () => null,
}));

vi.mock("@/contexts/PermissionsContext", () => ({
  usePermissions: vi.fn(() => ({
    permissions: { canCreateProjects: true },
    isPending: false,
  })),
}));

vi.mock("@/contexts/feature-toggles-provider", () => ({
  useIsFeatureEnabled: vi.fn(() => mockDemoDataEnabled),
}));

vi.mock("@/types/feature-toggles", () => ({
  FeatureToggleKeys: { DEMO_DATA_ENABLED: "demo_data_enabled" },
}));

vi.mock("./AgentOnboarding/AgentOnboardingContext", () => ({
  AGENT_ONBOARDING_KEY: "agent-onboarding",
  MANUAL_ONBOARDING_KEY: "manual-onboarding",
  AI_ASSISTED_OPIK_SKILLS_FEATURE_FLAG_KEY: "onboarding-integrations-3-options",
  DEFAULT_ONBOARDING_FLOW: "manual",
  GUIDED_MOBILE_ONBOARDING_FLOW_FEATURE_FLAG_KEY:
    "guided-mobile-onboarding-flow",
  GUIDED_MOBILE_ONBOARDING_VARIANTS: { CONTROL: "control", TEST: "test" },
  AGENT_ONBOARDING_STEPS: {
    SELECT_INTENT: "select-intent",
    AGENT_NAME: "agent-name",
    CONNECT_AGENT: "connect-agent",
    DEMO_LOADING: "demo-loading",
    DONE: "done",
  },
}));

// ── helpers ────────────────────────────────────────────────────────────────
const MANUAL_KEY = "manual-onboarding-test-ws";
const AGENT_KEY = "agent-onboarding-test-ws";

function setManualDone(value: boolean) {
  localStorageStore[MANUAL_KEY] = value;
}

function setAgentOnboardingState(
  state: { step: string; agentName?: string } | undefined,
) {
  localStorageStore[AGENT_KEY] = state;
}

// ──────────────────────────────────────────────────────────────────────────

describe("NewQuickstart — manual variant", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockNavigate.mockReset();
    for (const key of Object.keys(localStorageStore))
      delete localStorageStore[key];
    for (const key of Object.keys(localStorageSetters))
      delete localStorageSetters[key];
    mockVariant = "manual";
    mockMobileVariant = undefined;
    mockIsPhone = false;
    mockHasTraces = false;
    mockPollExpired = false;
    mockFirstTraceProjectId = null;
    mockDemoDataEnabled = true;
  });

  it("redirects to home when onboarding was already done at mount", () => {
    setManualDone(true);
    render(<NewQuickstart />);

    const nav = screen.getByTestId("navigate");
    expect(nav).toHaveAttribute("data-to", "/$workspaceName/home");
  });

  it("shows DemoLoadingContent when skip is pressed", () => {
    setManualDone(false);
    render(<NewQuickstart />);

    expect(
      screen.getByTestId("onboarding-integrations-page"),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByTestId("skip-btn"));

    expect(screen.getByTestId("demo-loading-content")).toBeInTheDocument();
  });

  it("marks onboarding done and navigates home when skip is pressed and demo data is disabled", () => {
    mockDemoDataEnabled = false;
    setManualDone(false);
    render(<NewQuickstart />);

    fireEvent.click(screen.getByTestId("skip-btn"));

    expect(localStorageSetters[MANUAL_KEY]).toHaveBeenCalledWith(true);
    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/$workspaceName/home",
      params: { workspaceName: "test-ws" },
    });
    expect(
      screen.queryByTestId("demo-loading-content"),
    ).not.toBeInTheDocument();
  });

  it("returns to integrations page when DemoLoadingContent onRetry is called", () => {
    setManualDone(false);
    render(<NewQuickstart />);

    fireEvent.click(screen.getByTestId("skip-btn"));
    expect(screen.getByTestId("demo-loading-content")).toBeInTheDocument();

    fireEvent.click(screen.getByText("Back to setup"));

    expect(
      screen.getByTestId("onboarding-integrations-page"),
    ).toBeInTheDocument();
  });

  describe("banner logic", () => {
    it("shows waiting banner when polling is active and no traces received", () => {
      mockHasTraces = false;
      mockPollExpired = false;
      setManualDone(false);
      render(<NewQuickstart />);

      const status = screen.getByTestId("logged-data-status");
      expect(status).toHaveAttribute("data-status", "waiting");
    });

    it("shows logged banner when traces have been received", () => {
      mockHasTraces = true;
      mockFirstTraceProjectId = "proj-123";
      mockPollExpired = false;
      setManualDone(false);
      render(<NewQuickstart />);

      const status = screen.getByTestId("logged-data-status");
      expect(status).toHaveAttribute("data-status", "logged");
    });

    it("hides banner when poll expired and no traces were received", () => {
      mockHasTraces = false;
      mockPollExpired = true;
      setManualDone(false);
      render(<NewQuickstart />);

      expect(
        screen.queryByTestId("logged-data-status"),
      ).not.toBeInTheDocument();
      expect(screen.queryByTestId("banner")).not.toBeInTheDocument();
    });

    it("still shows logged banner when poll expired but traces exist", () => {
      mockHasTraces = true;
      mockPollExpired = true;
      mockFirstTraceProjectId = "proj-456";
      setManualDone(false);
      render(<NewQuickstart />);

      const status = screen.getByTestId("logged-data-status");
      expect(status).toHaveAttribute("data-status", "logged");
    });
  });

  it("calls navigate to project logs when Explore is clicked after traces arrive", () => {
    mockHasTraces = true;
    mockFirstTraceProjectId = "proj-789";
    setManualDone(false);
    render(<NewQuickstart />);

    fireEvent.click(screen.getByTestId("explore-btn"));

    expect(mockNavigate).toHaveBeenCalledWith({
      to: "/$workspaceName/projects/$projectId/logs",
      params: { workspaceName: "test-ws", projectId: "proj-789" },
    });
  });
});

describe("NewQuickstart — non-manual variants", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    for (const key of Object.keys(localStorageStore))
      delete localStorageStore[key];
    for (const key of Object.keys(localStorageSetters))
      delete localStorageSetters[key];
    mockVariant = "control";
    mockMobileVariant = undefined;
    mockIsPhone = false;
    mockProjectData = undefined;
    mockProjectIsPending = false;
  });

  it("renders AgentOnboardingOverlay when onboarding is not done", () => {
    mockVariant = "control";
    setAgentOnboardingState({ step: "select-intent" });
    render(<NewQuickstart />);

    expect(screen.getByTestId("agent-onboarding-overlay")).toBeInTheDocument();
  });

  it("renders nothing while project lookup is pending after onboarding completes", () => {
    mockVariant = "control";
    mockProjectIsPending = true;
    setAgentOnboardingState({ step: "done", agentName: "my-agent" });
    const { container } = render(<NewQuickstart />);

    expect(container).toBeEmptyDOMElement();
  });

  it("redirects to project logs when project is found after onboarding", () => {
    mockVariant = "control";
    mockProjectData = { id: "proj-abc" };
    setAgentOnboardingState({ step: "done", agentName: "my-agent" });
    render(<NewQuickstart />);

    const nav = screen.getByTestId("navigate");
    expect(nav).toHaveAttribute(
      "data-to",
      "/$workspaceName/projects/$projectId/logs",
    );
  });

  it("redirects to home when project is not found after onboarding", () => {
    mockVariant = "control";
    mockProjectData = undefined;
    setAgentOnboardingState({ step: "done", agentName: "my-agent" });
    render(<NewQuickstart />);

    const nav = screen.getByTestId("navigate");
    expect(nav).toHaveAttribute("data-to", "/$workspaceName/home");
  });

  it("redirects to home when onboarding is done but there was no agent name", () => {
    mockVariant = "control";
    mockProjectData = undefined;
    setAgentOnboardingState({ step: "done" });
    render(<NewQuickstart />);

    const nav = screen.getByTestId("navigate");
    expect(nav).toHaveAttribute("data-to", "/$workspaceName/home");
  });
});

describe("NewQuickstart — guided mobile onboarding A/B test (OPIK-7476)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockNavigate.mockReset();
    for (const key of Object.keys(localStorageStore))
      delete localStorageStore[key];
    for (const key of Object.keys(localStorageSetters))
      delete localStorageSetters[key];
    mockVariant = "manual";
    mockIsPhone = true;
    mockHasTraces = false;
    mockPollExpired = false;
    mockFirstTraceProjectId = null;
    mockDemoDataEnabled = true;
    setManualDone(false);
  });

  it('renders the guided mobile onboarding flow for the "test" variant (Group B)', () => {
    mockMobileVariant = "test";
    render(<NewQuickstart />);

    expect(screen.getByTestId("mobile-onboarding")).toBeInTheDocument();
    expect(
      screen.queryByTestId("onboarding-integrations-page"),
    ).not.toBeInTheDocument();
  });

  it('renders the legacy onboarding flow for the "control" variant (Group A)', () => {
    mockMobileVariant = "control";
    render(<NewQuickstart />);

    // Control falls through to the same manual flow desktop users get (which
    // carries the Run button), not the guided mobile flow.
    expect(
      screen.getByTestId("onboarding-integrations-page"),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("mobile-onboarding")).not.toBeInTheDocument();
  });

  it("holds rendering behind a loader while the experiment bucket is unresolved", () => {
    mockMobileVariant = undefined;
    render(<NewQuickstart />);

    expect(screen.getByTestId("loader")).toBeInTheDocument();
    expect(screen.queryByTestId("mobile-onboarding")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("onboarding-integrations-page"),
    ).not.toBeInTheDocument();
  });

  it("does not gate desktop users on the mobile experiment flag", () => {
    mockIsPhone = false;
    mockMobileVariant = undefined;
    render(<NewQuickstart />);

    // No loader, no mobile flow — desktop renders its normal onboarding.
    expect(screen.queryByTestId("loader")).not.toBeInTheDocument();
    expect(screen.queryByTestId("mobile-onboarding")).not.toBeInTheDocument();
    expect(
      screen.getByTestId("onboarding-integrations-page"),
    ).toBeInTheDocument();
  });
});
