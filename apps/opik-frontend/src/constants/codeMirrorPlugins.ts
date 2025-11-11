import { ViewPlugin, ViewUpdate } from "@uiw/react-codemirror";
import { Decoration, DecorationSet, EditorView } from "@codemirror/view";

export const codeMirrorPromptTheme = EditorView.theme({
  "&": {
    fontSize: "0.875rem",
    cursor: "text",
  },
  "&.cm-focused": {
    outline: "none",
  },
  ".cm-line": {
    "padding-left": 0,
  },
  ".cm-scroller": {
    fontFamily: "inherit",
  },
  ".cm-placeholder": {
    color: "var(--codemirror-gutter)",
    fontWeight: 300,
  },
});

export const mustachePlugin = ViewPlugin.fromClass(
  class {
    decorations: DecorationSet;

    constructor(view: EditorView) {
      this.decorations = this.createDecorations(view);
    }

    update(update: ViewUpdate) {
      if (update.docChanged || update.viewportChanged) {
        this.decorations = this.createDecorations(update.view);
      }
    }

    createDecorations(view: EditorView): DecorationSet {
      const widgets = [];
      for (const { from, to } of view.visibleRanges) {
        const text = view.state.doc.sliceString(from, to);
        const regex = /{{(.*?)}}/g;
        let match;
        while ((match = regex.exec(text)) !== null) {
          const start = from + match.index;
          const end = start + match[0].length;
          widgets.push(
            Decoration.mark({ class: "text-[var(--color-green)]" }).range(
              start,
              end,
            ),
          );
        }
      }
      return Decoration.set(widgets);
    }
  },
  {
    decorations: (v) => v.decorations,
  },
);
