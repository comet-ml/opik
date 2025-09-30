import { ViewPlugin, ViewUpdate } from "@uiw/react-codemirror";
import { Decoration, DecorationSet, EditorView } from "@codemirror/view";

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

        // First pass: highlight image delimiters (<<<image>>> and <<</image>>>)
        const imageDelimiterRegex = /(<<<image>>>|<<<\/image>>>)/g;
        let match;
        while ((match = imageDelimiterRegex.exec(text)) !== null) {
          const start = from + match.index;
          const end = start + match[0].length;
          widgets.push(
            Decoration.mark({
              class: "text-[var(--color-orange)]",
            }).range(start, end),
          );
        }

        // Second pass: highlight all {{mustache}} variables (including those inside image tags)
        const mustacheRegex = /{{(.*?)}}/g;
        while ((match = mustacheRegex.exec(text)) !== null) {
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
