import React from "react";
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render } from "@testing-library/react";
import SandboxedView from "./SandboxedView";
import { KitPack } from "./kit/loadKit";

vi.mock("@/contexts/theme-provider", () => ({
  useTheme: () => ({ themeMode: "light" }),
}));

const HTML = "<script>kit.mount(() => kit.h('div'))</script>";
const PACKS: KitPack[] = [];

const renderView = () => {
  const onError = vi.fn();
  const view = (data: unknown) => (
    <SandboxedView
      html={HTML}
      data={data}
      kitVersion={1}
      packs={PACKS}
      title="Custom view"
      onError={onError}
    />
  );
  const result = render(view({ trace: { id: "a" } }));
  return { ...result, onError, view };
};

describe("SandboxedView", () => {
  it("keeps the frame after its own document loads", () => {
    const { container, onError } = renderView();
    const frame = container.querySelector("iframe")!;

    fireEvent.load(frame);

    expect(container.querySelector("iframe")).not.toBeNull();
    expect(onError).not.toHaveBeenCalled();
  });

  it("removes the frame and fails when the view navigates away", () => {
    const { container, onError } = renderView();
    const frame = container.querySelector("iframe")!;

    fireEvent.load(frame);
    fireEvent.load(frame);

    expect(container.querySelector("iframe")).toBeNull();
    expect(container).toHaveTextContent(
      "The custom view tried to open another page.",
    );
    expect(onError).toHaveBeenCalledTimes(1);
    expect(onError).toHaveBeenCalledWith(
      "The custom view tried to open another page.",
      "error",
    );
  });

  it("reports the blocked view again for the next sample", () => {
    const { container, onError, rerender, view } = renderView();
    const frame = container.querySelector("iframe")!;
    fireEvent.load(frame);
    fireEvent.load(frame);

    rerender(view({ trace: { id: "b" } }));

    expect(onError).toHaveBeenCalledTimes(2);
  });
});
