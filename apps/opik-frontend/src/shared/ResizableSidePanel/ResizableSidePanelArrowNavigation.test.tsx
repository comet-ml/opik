import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import ResizableSidePanelArrowNavigation from "./ResizableSidePanelArrowNavigation";

const buildNavigation = (overrides = {}) => ({
  hasPrevious: true,
  hasNext: true,
  onChange: vi.fn(),
  ...overrides,
});

describe("ResizableSidePanelArrowNavigation", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders nothing when horizontalNavigation is undefined", () => {
    const { container } = render(<ResizableSidePanelArrowNavigation />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders Previous and Next buttons when horizontalNavigation is provided", () => {
    render(
      <ResizableSidePanelArrowNavigation
        horizontalNavigation={buildNavigation()}
      />,
    );
    expect(
      screen.getByRole("button", { name: /Previous/ }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Next/ })).toBeInTheDocument();
  });

  it("calls onChange(-1) when Previous is clicked", () => {
    const navigation = buildNavigation();
    render(
      <ResizableSidePanelArrowNavigation horizontalNavigation={navigation} />,
    );
    fireEvent.click(screen.getByRole("button", { name: /Previous/ }));
    expect(navigation.onChange).toHaveBeenCalledWith(-1);
  });

  it("calls onChange(1) when Next is clicked", () => {
    const navigation = buildNavigation();
    render(
      <ResizableSidePanelArrowNavigation horizontalNavigation={navigation} />,
    );
    fireEvent.click(screen.getByRole("button", { name: /Next/ }));
    expect(navigation.onChange).toHaveBeenCalledWith(1);
  });

  it("disables Previous button when hasPrevious is false", () => {
    render(
      <ResizableSidePanelArrowNavigation
        horizontalNavigation={buildNavigation({ hasPrevious: false })}
      />,
    );
    expect(screen.getByRole("button", { name: /Previous/ })).toBeDisabled();
  });

  it("disables Next button when hasNext is false", () => {
    render(
      <ResizableSidePanelArrowNavigation
        horizontalNavigation={buildNavigation({ hasNext: false })}
      />,
    );
    expect(screen.getByRole("button", { name: /Next/ })).toBeDisabled();
  });

  it("triggers nav on the J key", () => {
    const navigation = buildNavigation();
    render(
      <ResizableSidePanelArrowNavigation horizontalNavigation={navigation} />,
    );
    fireEvent.keyDown(document, { key: "j" });
    expect(navigation.onChange).toHaveBeenCalledWith(-1);
  });

  it("triggers nav on the K key", () => {
    const navigation = buildNavigation();
    render(
      <ResizableSidePanelArrowNavigation horizontalNavigation={navigation} />,
    );
    fireEvent.keyDown(document, { key: "k" });
    expect(navigation.onChange).toHaveBeenCalledWith(1);
  });

  it("does not trigger nav when ignoreHotkeys is true", () => {
    const navigation = buildNavigation();
    render(
      <ResizableSidePanelArrowNavigation
        horizontalNavigation={navigation}
        ignoreHotkeys
      />,
    );
    fireEvent.keyDown(document, { key: "j" });
    fireEvent.keyDown(document, { key: "k" });
    expect(navigation.onChange).not.toHaveBeenCalled();
  });

  it("does not call onChange via J when hasPrevious is false", () => {
    const navigation = buildNavigation({ hasPrevious: false });
    render(
      <ResizableSidePanelArrowNavigation horizontalNavigation={navigation} />,
    );
    fireEvent.keyDown(document, { key: "j" });
    expect(navigation.onChange).not.toHaveBeenCalled();
  });

  it("does not call onChange via K when hasNext is false", () => {
    const navigation = buildNavigation({ hasNext: false });
    render(
      <ResizableSidePanelArrowNavigation horizontalNavigation={navigation} />,
    );
    fireEvent.keyDown(document, { key: "k" });
    expect(navigation.onChange).not.toHaveBeenCalled();
  });
});
