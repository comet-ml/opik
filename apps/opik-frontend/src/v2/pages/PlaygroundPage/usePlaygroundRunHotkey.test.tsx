import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, renderHook, screen } from "@testing-library/react";

import usePlaygroundRunHotkey from "./usePlaygroundRunHotkey";

const onRunAll = vi.fn();
const onRunPrompt = vi.fn();
const editorKeyDown = vi.fn();
const dialogKeyDown = vi.fn();

const renderPlayground = ({
  promptCount = 2,
  canRunAll = true,
  canRunPrompt = () => true,
  withDialog = false,
}: {
  promptCount?: number;
  canRunAll?: boolean;
  canRunPrompt?: (promptId: string) => boolean;
  withDialog?: boolean;
} = {}) => {
  render(
    <>
      <div data-prompt-id="a">
        <textarea aria-label="editor a" onKeyDown={editorKeyDown} />
      </div>
      <div data-prompt-id="b">
        <textarea aria-label="editor b" onKeyDown={editorKeyDown} />
      </div>
      <input aria-label="outside" />
      {withDialog && (
        <div role="dialog">
          <textarea aria-label="dialog field" onKeyDown={dialogKeyDown} />
        </div>
      )}
    </>,
  );

  renderHook(() =>
    usePlaygroundRunHotkey({
      promptCount,
      canRunAll,
      canRunPrompt,
      onRunAll,
      onRunPrompt,
    }),
  );
};

const pressModEnter = (label: string, init: KeyboardEventInit = {}) =>
  fireEvent.keyDown(screen.getByLabelText(label), {
    key: "Enter",
    metaKey: true,
    ...init,
  });

beforeEach(() => {
  vi.clearAllMocks();
});

describe("usePlaygroundRunHotkey", () => {
  it("should run only the prompt that holds the focus", () => {
    renderPlayground();

    pressModEnter("editor b");

    expect(onRunPrompt).toHaveBeenCalledTimes(1);
    expect(onRunPrompt).toHaveBeenCalledWith("b");
    expect(onRunAll).not.toHaveBeenCalled();
  });

  it("should keep Mod+Enter away from the editor so it adds no blank line", () => {
    renderPlayground();

    const notPrevented = pressModEnter("editor a");

    expect(notPrevented).toBe(false);
    expect(editorKeyDown).not.toHaveBeenCalled();
  });

  it("should run all prompts when the focus is outside every prompt", () => {
    renderPlayground();

    pressModEnter("outside", { metaKey: false, ctrlKey: true });

    expect(onRunAll).toHaveBeenCalledTimes(1);
    expect(onRunPrompt).not.toHaveBeenCalled();
  });

  it("should run all when there is only one prompt, even with the focus in it", () => {
    renderPlayground({ promptCount: 1 });

    pressModEnter("editor a");

    expect(onRunAll).toHaveBeenCalledTimes(1);
    expect(onRunPrompt).not.toHaveBeenCalled();
  });

  it("should leave Shift+Enter to the editor as a newline", () => {
    renderPlayground();

    const notPrevented = pressModEnter("editor b", {
      metaKey: false,
      shiftKey: true,
    });

    expect(notPrevented).toBe(true);
    expect(editorKeyDown).toHaveBeenCalledTimes(1);
    expect(onRunAll).not.toHaveBeenCalled();
    expect(onRunPrompt).not.toHaveBeenCalled();
  });

  it("should do nothing while a dialog is open and let the dialog handle the key", () => {
    renderPlayground({ withDialog: true });

    pressModEnter("dialog field");
    pressModEnter("editor b");

    expect(dialogKeyDown).toHaveBeenCalledTimes(1);
    expect(onRunAll).not.toHaveBeenCalled();
    expect(onRunPrompt).not.toHaveBeenCalled();
  });

  it("should not fire while an IME composition is in progress", () => {
    renderPlayground();

    pressModEnter("editor b", { isComposing: true });

    expect(onRunPrompt).not.toHaveBeenCalled();
  });

  it("should not fall back to running all when the focused prompt can't run", () => {
    const canRunPrompt = vi.fn((promptId: string) => promptId !== "b");
    renderPlayground({ canRunPrompt });

    pressModEnter("editor b");

    expect(canRunPrompt).toHaveBeenCalledWith("b");
    expect(onRunPrompt).not.toHaveBeenCalled();
    expect(onRunAll).not.toHaveBeenCalled();
  });

  it("should not run all when running all is not allowed", () => {
    renderPlayground({ canRunAll: false });

    pressModEnter("outside");

    expect(onRunAll).not.toHaveBeenCalled();
  });
});
