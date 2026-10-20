import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import WidgetConfigDialog from "./WidgetConfigDialog";
import usePluginsStore from "@/store/PluginsStore";
import { FeatureToggleKeys } from "@/types/feature-toggles";

let enabledToggles: Set<FeatureToggleKeys>;

vi.mock("@/contexts/feature-toggles-provider", () => ({
  useIsFeatureEnabled: (key: FeatureToggleKeys) => enabledToggles.has(key),
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
      "the workspace is not on the Custom Charts allowlist",
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

  it("hides the prompt when editing a widget", () => {
    renderDialog("widget");

    expect(screen.queryByText(OLLIE_PROMPT)).not.toBeInTheDocument();
  });
});
