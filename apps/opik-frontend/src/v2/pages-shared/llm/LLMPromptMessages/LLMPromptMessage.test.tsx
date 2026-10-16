import React, { createRef } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render } from "@testing-library/react";
import type { EditorView } from "@codemirror/view";

import LLMPromptMessage, {
  LLMPromptMessageHandle,
} from "@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessage";
import { TooltipProvider } from "@/ui/tooltip";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";

const editor = vi.hoisted(() => ({
  create: null as null | (() => void),
}));

// CodeMirror doesn't mount under jsdom. The stub hands the test the moment the
// editor gets created, which in the real wrapper is a render after mount.
vi.mock("@uiw/react-codemirror", async (importOriginal) => {
  const actual = await importOriginal<object>();
  return {
    ...actual,
    default: ({
      onCreateEditor,
    }: {
      onCreateEditor?: (view: EditorView) => void;
    }) => {
      editor.create = () =>
        onCreateEditor?.(createdView as unknown as EditorView);
      return <div data-testid="codemirror-stub" />;
    },
  };
});

const TEXT = "Summarise this";

const createdView = {
  state: { doc: { length: TEXT.length } },
  dispatch: vi.fn(),
  focus: vi.fn(),
};

const message: LLMMessage = {
  id: "message-1",
  role: LLM_MESSAGE_ROLE.user,
  content: TEXT,
};

const renderMessage = () => {
  const ref = createRef<LLMPromptMessageHandle>();
  render(
    <TooltipProvider>
      <LLMPromptMessage
        ref={ref}
        message={message}
        hideRemoveButton
        hideDragButton
        hidePromptActions
        onRemoveMessage={vi.fn()}
        onDuplicateMessage={vi.fn()}
        onChangeMessage={vi.fn()}
      />
    </TooltipProvider>,
  );
  return ref;
};

describe("LLMPromptMessage focus", () => {
  afterEach(() => {
    cleanup();
    editor.create = null;
    createdView.dispatch.mockClear();
    createdView.focus.mockClear();
  });

  it("should wait for the editor when focus is asked for before it exists", () => {
    const ref = renderMessage();

    act(() => ref.current?.focus());

    expect(createdView.focus).not.toHaveBeenCalled();

    act(() => editor.create?.());

    expect(createdView.dispatch).toHaveBeenCalledWith({
      selection: { anchor: TEXT.length },
    });
    expect(createdView.focus).toHaveBeenCalledTimes(1);
  });

  it("should focus at the end of the text once the editor exists", () => {
    const ref = renderMessage();
    act(() => editor.create?.());

    act(() => ref.current?.focus());

    expect(createdView.dispatch).toHaveBeenCalledWith({
      selection: { anchor: TEXT.length },
    });
    expect(createdView.focus).toHaveBeenCalledTimes(1);
  });

  it("should not focus a new editor nobody asked to focus", () => {
    renderMessage();

    act(() => editor.create?.());

    expect(createdView.focus).not.toHaveBeenCalled();
  });

  it("should use a waiting focus request only once", () => {
    const ref = renderMessage();
    act(() => ref.current?.focus());
    act(() => editor.create?.());

    act(() => editor.create?.());

    expect(createdView.focus).toHaveBeenCalledTimes(1);
  });
});
