import {
  codeFolding,
  foldable,
  foldedRanges,
  foldEffect,
  foldKeymap,
  unfoldEffect,
} from "@codemirror/language";
import {
  Decoration,
  DecorationSet,
  EditorView,
  keymap,
  ViewPlugin,
  ViewUpdate,
  WidgetType,
} from "@codemirror/view";
import { Extension, RangeSetBuilder } from "@codemirror/state";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { ChevronDown, ChevronRight } from "lucide-react";

// CodeMirror widgets are imperative DOM, so we render the lucide icons used
// elsewhere in the trace view to static markup once at module load and reuse
// the resulting SVG strings. This keeps the fold chevrons in sync with lucide
// without paying a render cost per widget.
const renderChevron = (Icon: typeof ChevronDown) =>
  renderToStaticMarkup(createElement(Icon, { size: 14, strokeWidth: 2.25 }));

const CHEVRON_DOWN = renderChevron(ChevronDown);
const CHEVRON_RIGHT = renderChevron(ChevronRight);

// Clickable chevron rendered inline at the end of an expanded foldable line.
// Clicking it collapses the JSON object / array or YAML block it heads.
class FoldChevronWidget extends WidgetType {
  constructor(
    readonly from: number,
    readonly to: number,
  ) {
    super();
  }

  eq(other: FoldChevronWidget) {
    return other.from === this.from && other.to === this.to;
  }

  toDOM(view: EditorView) {
    const button = document.createElement("span");
    button.className = "cm-inline-fold-marker";
    button.setAttribute("role", "button");
    button.setAttribute("aria-label", "Collapse");
    button.innerHTML = CHEVRON_DOWN;
    button.onmousedown = (event) => {
      // Prevent the read-only editor from moving the caret / selection.
      event.preventDefault();
      event.stopPropagation();
      view.dispatch({
        effects: foldEffect.of({ from: this.from, to: this.to }),
      });
    };
    return button;
  }

  ignoreEvent() {
    return false;
  }
}

const isRangeFolded = (
  state: EditorView["state"],
  range: { from: number; to: number },
) => {
  let folded = false;
  foldedRanges(state).between(range.from, range.to, (from, to) => {
    if (from === range.from && to === range.to) folded = true;
  });
  return folded;
};

const buildFoldMarkers = (view: EditorView): DecorationSet => {
  const builder = new RangeSetBuilder<Decoration>();
  const { state } = view;

  for (const { from, to } of view.visibleRanges) {
    let pos = from;
    while (pos <= to) {
      const line = state.doc.lineAt(pos);
      const range = foldable(state, line.from, line.to);
      // Only decorate expanded lines — collapsed ones surface the placeholder.
      if (range && !isRangeFolded(state, range)) {
        builder.add(
          line.to,
          line.to,
          Decoration.widget({
            widget: new FoldChevronWidget(range.from, range.to),
            side: 1,
          }),
        );
      }
      pos = line.to + 1;
    }
  }

  return builder.finish();
};

const foldToggledBy = (update: ViewUpdate) =>
  update.transactions.some((tr) =>
    tr.effects.some((e) => e.is(foldEffect) || e.is(unfoldEffect)),
  );

const foldMarkers = ViewPlugin.fromClass(
  class {
    decorations: DecorationSet;

    constructor(view: EditorView) {
      this.decorations = buildFoldMarkers(view);
    }

    update(update: ViewUpdate) {
      if (
        update.docChanged ||
        update.viewportChanged ||
        foldToggledBy(update)
      ) {
        this.decorations = buildFoldMarkers(update.view);
      }
    }
  },
  { decorations: (v) => v.decorations },
);

const foldingTheme = EditorView.baseTheme({
  ".cm-inline-fold-marker": {
    display: "inline-flex",
    alignItems: "center",
    verticalAlign: "middle",
    marginLeft: "4px",
    color: "hsl(var(--muted-slate))",
    cursor: "pointer",
    opacity: "0.5",
    transition: "opacity 0.1s ease-in-out, color 0.1s ease-in-out",
  },
  ".cm-inline-fold-marker:hover": {
    opacity: "1",
    color: "hsl(var(--primary))",
  },
  ".cm-inline-fold-placeholder": {
    display: "inline-flex",
    alignItems: "center",
    gap: "1px",
    background: "hsl(var(--muted))",
    borderRadius: "4px",
    color: "hsl(var(--text-secondary))",
    margin: "0 4px",
    padding: "0 4px",
    verticalAlign: "middle",
    cursor: "pointer",
  },
});

// Inline placeholder shown in place of a collapsed block; clicking expands it.
const createFoldPlaceholder = (
  _view: EditorView,
  onclick: (event: Event) => void,
) => {
  const el = document.createElement("span");
  el.className = "cm-inline-fold-placeholder";
  el.setAttribute("role", "button");
  el.setAttribute("aria-label", "Expand");
  el.innerHTML = `${CHEVRON_RIGHT}<span>…</span>`;
  el.onclick = onclick;
  return el;
};

/**
 * Enables collapse / expand of JSON objects, arrays and YAML blocks inside the
 * read-only CodeMirror viewers used in the trace / span detail view. Instead of
 * a left fold gutter, the chevron sits inline at the end of each foldable line;
 * collapsed blocks render a clickable placeholder that expands them again.
 */
export const createFoldingExtension = (): Extension => [
  codeFolding({ placeholderDOM: createFoldPlaceholder }),
  foldMarkers,
  foldingTheme,
  keymap.of(foldKeymap),
];
