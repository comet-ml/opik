import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import ColorIndicator from "./ColorIndicator";
import { TooltipProvider } from "@/ui/tooltip";

// The indicator's own persistence path is not what this file is about: it covers the interaction
// contract of the nested Radix triggers, which is what customers hit when they try to recolour a
// series (OPIK_7840 — the control was reported as missing because it was hard to reach).
vi.mock("@/hooks/useUpdateColorMapping", () => ({
  default: () => ({
    updateColor: vi.fn(),
    previewColor: {},
    setPreviewColor: vi.fn(),
    isPending: false,
  }),
}));

const renderIndicator = (onParentClick?: () => void) => {
  const utils = render(
    <TooltipProvider delayDuration={700}>
      <div onClick={onParentClick}>
        <ColorIndicator label="domain-agent" color="#491b7e" variant="dot" />
      </div>
    </TooltipProvider>,
  );

  // The indicator renders no text, so the trigger is found by the role Radix gives it.
  const trigger = utils.container.querySelector(
    "[data-state]",
  ) as HTMLElement | null;

  if (!trigger) throw new Error("colour indicator trigger not rendered");

  return { ...utils, trigger };
};

describe("ColorIndicator", () => {
  it("opens the colour picker when the indicator is clicked", async () => {
    const { trigger } = renderIndicator();

    fireEvent.click(trigger);

    // The hex field is the picker's only stable, user-visible landmark.
    expect(await screen.findByPlaceholderText("#000000")).toBeInTheDocument();
  });

  it("does not fire a surrounding click handler when the indicator is clicked", async () => {
    // In a chart legend the label around the dot navigates to filtered traces. Opening the picker
    // must not also trigger that navigation.
    const onParentClick = vi.fn();
    const { trigger } = renderIndicator(onParentClick);

    fireEvent.click(trigger);

    await screen.findByPlaceholderText("#000000");
    expect(onParentClick).not.toHaveBeenCalled();
  });

  it("gives the indicator a pointer target larger than the dot itself", () => {
    // The dot is 6px. Without the enlarged target it is effectively unhittable, which is why the
    // feature read as missing. The dot's own size stays unchanged.
    const { trigger } = renderIndicator();

    expect(trigger.className).toContain("size-1.5");
    expect(trigger.className).toMatch(/before:-inset-/);
  });
});
