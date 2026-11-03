import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { AssistantSidebarBridge } from "@/types/assistant-sidebar";
import AssistantSidebar from "./AssistantSidebar";

const hooks = vi.hoisted(() => ({
  useAssistantBackend: vi.fn(),
}));

vi.mock("@tanstack/react-router", () => ({
  useParams: () => ({}),
  useRouter: () => ({ navigate: vi.fn() }),
  useRouterState: () => undefined,
}));
vi.mock("@tanstack/react-query", () => ({
  useQueryClient: () => ({}),
}));
vi.mock("@/store/AppStore", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/store/AppStore")>();
  return { ...actual, useActiveWorkspaceName: () => "my-workspace" };
});
vi.mock("@/contexts/theme-provider", () => ({
  useTheme: () => ({ themeMode: "light" }),
}));
vi.mock("@/hooks/useWorkspaceColorMap", () => ({
  default: () => ({ colorMap: {} }),
}));
vi.mock("@/lib/charts/vega", () => ({ renderVegaChart: vi.fn() }));
vi.mock("@/ui/use-toast", () => ({ useToast: () => ({ toast: vi.fn() }) }));
vi.mock("@/plugins/comet/useWorkspace", () => ({ default: () => null }));
vi.mock("@/plugins/comet/useAssistantBackend", () => ({
  default: hooks.useAssistantBackend,
}));
vi.mock("@/plugins/comet/useAssistantManifest", () => ({
  default: () => ({ scriptUrl: "", shellUrl: "about:blank", version: "1" }),
}));
vi.mock("@/api/projects/useProjectById", () => ({
  default: () => ({ data: undefined }),
}));
vi.mock("@/hooks/useProjectOnboardingStats", () => ({ default: () => null }));
vi.mock("@/hooks/useRunnerBridgeSync", () => ({
  default: () => ({ handleRequestPair: vi.fn() }),
}));
vi.mock("@/plugins/comet/OllieLoader", () => ({
  default: () => <div data-testid="ollie-loader" />,
}));
vi.mock("@/plugins/comet/AssistantErrorState", () => ({ default: () => null }));

const givenBackendReady = (isReady: boolean) =>
  hooks.useAssistantBackend.mockReturnValue({
    backendUrl: "https://pod.example.com",
    probeUrl: "https://pod.example.com/health",
    isReady,
    error: null,
    phase: isReady ? "ready" : "starting",
    retry: vi.fn(),
    retryCount: 0,
  });

const renderSidebar = () =>
  render(<AssistantSidebar onWidthChange={vi.fn()} />);

const bridge = () => window.opikBridge as AssistantSidebarBridge;

const iframe = () =>
  screen.getByTestId("ollie-assistant-iframe") as HTMLIFrameElement;

// happy-dom doesn't load iframe documents; stand one in and report it loaded.
const loadIframeDocument = () => {
  const documentWindow = new EventTarget();
  Object.defineProperty(iframe(), "contentWindow", {
    value: documentWindow,
    configurable: true,
  });
  iframe().dispatchEvent(new Event("load"));
  return documentWindow;
};

// What a console closure does once its iframe is gone: Chromium returns null for its localStorage.
const deadFrameListener = vi.fn(({ isOpen }: { isOpen: boolean }) =>
  (null as unknown as Storage).setItem(
    "assistant-sidebar-open",
    String(isOpen),
  ),
);

describe("AssistantSidebar — console listeners of a gone iframe document", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    givenBackendReady(true);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("drops them when the iframe is removed and a new one mounts", () => {
    const { rerender } = renderSidebar();
    bridge().subscribe("visibility:changed", deadFrameListener);

    givenBackendReady(false);
    rerender(<AssistantSidebar onWidthChange={vi.fn()} />);
    expect(screen.getByTestId("ollie-loader")).toBeInTheDocument();
    givenBackendReady(true);
    rerender(<AssistantSidebar onWidthChange={vi.fn()} />);

    expect(() => bridge().setOpen?.(true)).not.toThrow();
    expect(deadFrameListener).not.toHaveBeenCalled();
  });

  it("drops them when the iframe document unloads, and keeps the next document's", () => {
    renderSidebar();
    const firstDocument = loadIframeDocument();
    bridge().subscribe("visibility:changed", deadFrameListener);

    firstDocument.dispatchEvent(new Event("pagehide"));
    loadIframeDocument();
    const live = vi.fn();
    bridge().subscribe("visibility:changed", live);
    bridge().setOpen?.(true);

    expect(deadFrameListener).not.toHaveBeenCalled();
    expect(live).toHaveBeenCalledWith({ isOpen: true });
  });

  it("does not call them on unmount", () => {
    const { unmount } = renderSidebar();
    bridge().subscribe("visibility:changed", deadFrameListener);

    expect(() => unmount()).not.toThrow();
    expect(deadFrameListener).not.toHaveBeenCalled();
  });

  // The production crash: "Edit with Ollie" reached a dead closure that slipped past the cleanup.
  it("does not crash the page when Edit with Ollie reaches one", () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    renderSidebar();
    bridge().subscribe("visibility:changed", deadFrameListener);

    expect(() =>
      bridge().startConversation("Edit this annotator view"),
    ).not.toThrow();
    expect(deadFrameListener).toHaveBeenCalledTimes(1);
  });
});
