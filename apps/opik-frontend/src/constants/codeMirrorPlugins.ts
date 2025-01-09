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
      let widgets = [];
      for (let { from, to } of view.visibleRanges) {
        let text = view.state.doc.sliceString(from, to);
        let regex = /{{(.*?)}}/g;
        let match;
        while ((match = regex.exec(text)) !== null) {
          let start = from + match.index;
          let end = start + match[0].length;
          widgets.push(
            Decoration.mark({ class: "text-[#19A979]" }).range(start, end),
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
