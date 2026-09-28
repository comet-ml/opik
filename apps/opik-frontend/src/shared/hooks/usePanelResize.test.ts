import { describe, it, expect, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { createRef } from "react";

import usePanelResize from "./usePanelResize";

const renderResize = (enabled = true) => {
  const elementRef = createRef<HTMLElement>();
  (elementRef as { current: HTMLElement }).current =
    document.createElement("div");

  return renderHook(
    ({ enabled: isEnabled }) =>
      usePanelResize({
        panelId: "test-panel",
        hostWidth: 1000,
        elementRef,
        enabled: isEnabled,
      }),
    { initialProps: { enabled } },
  );
};

afterEach(() => {
  document.body.style.userSelect = "";
  localStorage.clear();
});

describe("usePanelResize", () => {
  it("releases the text selection lock when a drag ends", () => {
    const { result } = renderResize();

    act(() => result.current.startResize());
    expect(document.body.style.userSelect).toBe("none");

    act(() => {
      window.dispatchEvent(new MouseEvent("mouseup"));
    });

    expect(document.body.style.userSelect).toBe("");
  });

  it("releases the text selection lock when it unmounts mid drag", () => {
    const { result, unmount } = renderResize();

    act(() => result.current.startResize());
    expect(document.body.style.userSelect).toBe("none");

    unmount();

    expect(document.body.style.userSelect).toBe("");
  });

  it("releases the text selection lock when resizing is disabled mid drag", () => {
    const { result, rerender } = renderResize();

    act(() => result.current.startResize());
    expect(document.body.style.userSelect).toBe("none");

    rerender({ enabled: false });

    expect(document.body.style.userSelect).toBe("");
  });
});
