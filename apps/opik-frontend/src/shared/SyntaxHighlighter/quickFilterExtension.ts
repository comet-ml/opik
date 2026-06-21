import {
  Decoration,
  DecorationSet,
  EditorView,
  ViewPlugin,
  ViewUpdate,
  WidgetType,
} from "@codemirror/view";
import { Extension, RangeSetBuilder } from "@codemirror/state";
import { syntaxTree } from "@codemirror/language";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { Plus } from "lucide-react";
import { JsonValue } from "@/types/shared";
import {
  QuickFilterMode,
  collectQuickFilterTargets,
} from "@/shared/SyntaxHighlighter/quickFilterPaths";

export type QuickFilterCodeConfig = {
  canFilter: (path: string) => boolean;
  onFilter: (path: string, value: JsonValue) => void;
};

const PLUS_ICON = renderToStaticMarkup(
  createElement(Plus, { size: 14, strokeWidth: 2.25 }),
);

// Inline "+" rendered at the end of a leaf attribute line; revealed on line
// hover. Clicking seeds a filter for that attribute.
class QuickFilterWidget extends WidgetType {
  constructor(
    readonly path: string,
    readonly value: string,
    readonly onFilter: QuickFilterCodeConfig["onFilter"],
  ) {
    super();
  }

  eq(other: QuickFilterWidget) {
    return other.path === this.path && other.value === this.value;
  }

  toDOM() {
    const button = document.createElement("span");
    button.className = "cm-quick-filter-add";
    button.setAttribute("role", "button");
    button.setAttribute("tabindex", "0");
    button.setAttribute("aria-label", "Filter by this attribute");
    button.title = "Filter by this attribute";
    button.innerHTML = PLUS_ICON;
    const activate = (event: Event) => {
      // Keep the read-only editor from moving the caret / selection.
      event.preventDefault();
      event.stopPropagation();
      this.onFilter(this.path, this.value);
    };
    button.onmousedown = activate;
    button.onkeydown = (event) => {
      if (event.key === "Enter" || event.key === " ") activate(event);
    };
    return button;
  }

  ignoreEvent() {
    return false;
  }
}

const quickFilterTheme = EditorView.baseTheme({
  ".cm-quick-filter-add": {
    display: "inline-flex",
    alignItems: "center",
    verticalAlign: "middle",
    marginLeft: "6px",
    color: "var(--codemirror-gutter)",
    cursor: "pointer",
    opacity: "0",
    transition: "opacity 0.1s ease-in-out, color 0.1s ease-in-out",
  },
  ".cm-line:hover .cm-quick-filter-add": {
    opacity: "0.55",
  },
  ".cm-quick-filter-add:hover, .cm-quick-filter-add:focus-visible": {
    opacity: "1",
    color: "hsl(var(--text-primary))",
    outline: "none",
  },
});

/**
 * Adds a hover "+" affordance to each filterable leaf attribute in the
 * read-only JSON / YAML viewers. The attribute path and value are resolved
 * from the CodeMirror syntax tree, so the raw code view is preserved exactly.
 */
export const createQuickFilterExtension = (
  mode: QuickFilterMode,
  config: QuickFilterCodeConfig,
): Extension => {
  const build = (view: EditorView): DecorationSet => {
    const doc = view.state.doc.toString();
    const tree = syntaxTree(view.state);
    const targets = view.visibleRanges.flatMap(({ from, to }) =>
      collectQuickFilterTargets(tree, doc, mode, from, to),
    );

    const builder = new RangeSetBuilder<Decoration>();
    targets
      .filter((target) => config.canFilter(target.path))
      .sort((a, b) => a.pos - b.pos)
      .forEach((target) => {
        builder.add(
          target.pos,
          target.pos,
          Decoration.widget({
            widget: new QuickFilterWidget(
              target.path,
              target.value,
              config.onFilter,
            ),
            side: 1,
          }),
        );
      });

    return builder.finish();
  };

  const plugin = ViewPlugin.fromClass(
    class {
      decorations: DecorationSet;

      constructor(view: EditorView) {
        this.decorations = build(view);
      }

      update(update: ViewUpdate) {
        if (update.docChanged || update.viewportChanged) {
          this.decorations = build(update.view);
        }
      }
    },
    { decorations: (plugin) => plugin.decorations },
  );

  return [plugin, quickFilterTheme];
};
