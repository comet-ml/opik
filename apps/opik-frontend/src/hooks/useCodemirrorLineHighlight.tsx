import { RangeSetBuilder, ViewPlugin, ViewUpdate } from "@uiw/react-codemirror";
import { Decoration, EditorView } from "@codemirror/view";

const HIGHLIGHT_CLASS = Decoration.line({
  attributes: { class: "comet-line-highlight" },
});

function highlightLine(view: EditorView, lines: number[] = []) {
  const builder = new RangeSetBuilder<Decoration>();
  for (const { from, to } of view.visibleRanges) {
    for (let pos = from; pos <= to; ) {
      const line = view.state.doc.lineAt(pos);
      if (lines.includes(line.number))
        builder.add(line.from, line.from, HIGHLIGHT_CLASS);
      pos = line.to + 1;
    }
  }
  return builder.finish();
}

type CodemirrorLineHighlightProps = {
  lines?: number[];
};

export const useCodemirrorLineHighlight = ({
  lines,
}: CodemirrorLineHighlightProps) => {
  return ViewPlugin.fromClass(
    class {
      decorations = Decoration.none;
      update(update: ViewUpdate) {
        if (lines && lines.length > 0) {
          this.decorations = highlightLine(update.view, lines);
        }
      }
    },
    {
      decorations: (v) => v.decorations,
    },
  );
};
