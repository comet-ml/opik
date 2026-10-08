import { afterEach, describe, expect, it } from "vitest";
import { EditorSelection, EditorState } from "@codemirror/state";
import { EditorView } from "@codemirror/view";
import { deleteCharBackward } from "@codemirror/commands";
import { codeMirrorPromptTheme } from "./codeMirrorPlugins";

describe("codeMirrorPromptTheme", () => {
  let view: EditorView | undefined;

  afterEach(() => {
    view?.destroy();
    view = undefined;
  });

  const backspaceAtEnd = (doc: string) => {
    view = new EditorView({
      state: EditorState.create({
        doc,
        selection: EditorSelection.cursor(doc.length),
        extensions: codeMirrorPromptTheme,
      }),
      parent: document.body,
    });
    deleteCharBackward(view);
    return view.state;
  };

  it("turns the cursor forward when Backspace empties the editor", () => {
    const state = backspaceAtEnd("a");

    expect(state.doc.length).toBe(0);
    expect(state.selection.main.assoc).toBe(1);
  });

  it("leaves the cursor as Backspace set it while text remains", () => {
    const state = backspaceAtEnd("ab");

    expect(state.doc.toString()).toBe("a");
    expect(state.selection.main.assoc).toBe(-1);
  });
});
