import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import WidgetConfigDialog from "./WidgetConfigDialog";
import usePluginsStore from "@/store/PluginsStore";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import { AssistantSidebarBridge } from "@/types/assistant-sidebar";
import { useDashboardStore } from "@/store/DashboardStore";

let enabledToggles: Set<FeatureToggleKeys>;

vi.mock("@/contexts/feature-toggles-provider", () => ({
  useIsFeatureEnabled: (key: FeatureToggleKeys) => enabledToggles.has(key),
}));

vi.mock("@/lib/analytics/tracking", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  trackEvent: vi.fn(),
}));
vi.mock("./WidgetTypeSelector", () => ({ default: () => null }));
vi.mock("./WidgetConfigPreview", () => ({ default: () => null }));

const OLLIE_PROMPT = /Need a custom widget\?/;

const renderDialog = (widgetId?: string) =>
  render(
    <WidgetConfigDialog
      open
      onOpenChange={vi.fn()}
      sectionId="section"
      widgetId={widgetId}
      onSave={vi.fn()}
    />,
  );

describe("WidgetConfigDialog Ollie prompt", () => {
  beforeEach(() => {
    enabledToggles = new Set([
      FeatureToggleKeys.OLLIE_ENABLED,
      FeatureToggleKeys.CUSTOM_CHARTS_ENABLED_FOR_WORKSPACE,
    ]);
    usePluginsStore.setState({ AssistantSidebar: () => null });
  });

  afterEach(() => {
    usePluginsStore.setState({ AssistantSidebar: null });
  });

  it("offers Ollie when adding a widget in a Custom Charts workspace", () => {
    renderDialog();

    expect(screen.getByText(OLLIE_PROMPT)).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Ask Ollie" }),
    ).toBeInTheDocument();
  });

  it.each([
    ["Ollie is off", FeatureToggleKeys.OLLIE_ENABLED],
    [
      "Custom Charts is disabled",
      FeatureToggleKeys.CUSTOM_CHARTS_ENABLED_FOR_WORKSPACE,
    ],
  ])("hides the prompt when %s", (_, toggle) => {
    enabledToggles.delete(toggle);

    renderDialog();

    expect(screen.queryByText(OLLIE_PROMPT)).not.toBeInTheDocument();
  });

  it("hides the prompt without the assistant sidebar", () => {
    usePluginsStore.setState({ AssistantSidebar: null });

    renderDialog();

    expect(screen.queryByText(OLLIE_PROMPT)).not.toBeInTheDocument();
  });

  it("hands Ollie the same entry id it reports, so BI can match the click to its session", () => {
    const startConversation = vi.fn();
    window.opikBridge = {
      startConversation,
    } as unknown as AssistantSidebarBridge;
    useDashboardStore.setState({
      sections: [{ id: "section", title: "Spend", widgets: [], layout: [] }],
    } as never);

    renderDialog();
    fireEvent.click(screen.getByRole("button", { name: "Ask Ollie" }));

    const [, options] = startConversation.mock.calls[0];
    expect(options).toEqual({
      origin: "describe_widget",
      entryId: expect.any(String),
      // The section's id goes with its title: Ollie adds by id, since titles can repeat or be renamed.
      section: "Spend",
      sectionId: "section",
    });
    expect(trackEvent).toHaveBeenCalledWith(
      OpikEvent.DASHBOARD_ASK_OLLIE_CLICKED,
      expect.objectContaining({ entry_id: options.entryId }),
    );
    delete window.opikBridge;
  });

  it("hides the prompt when editing a widget", () => {
    renderDialog("widget");

    expect(screen.queryByText(OLLIE_PROMPT)).not.toBeInTheDocument();
  });
});
