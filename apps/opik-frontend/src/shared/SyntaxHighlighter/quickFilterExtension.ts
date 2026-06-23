import {
  Decoration,
  DecorationSet,
  EditorView,
  ViewPlugin,
  ViewUpdate,
  WidgetType,
} from "@codemirror/view";
import {
  Extension,
  RangeSetBuilder,
  StateEffect,
  StateField,
} from "@codemirror/state";
import { syntaxTree } from "@codemirror/language";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { ListFilter } from "lucide-react";
import { JsonValue } from "@/types/shared";
import {
  QuickFilterMode,
  collectQuickFilterTargets,
} from "@/shared/SyntaxHighlighter/quickFilterPaths";

export type QuickFilterCodeConfig = {
  canFilter: (path: string) => boolean;
  onFilter: (path: string, value: JsonValue) => void;
};

const FILTER_ICON = renderToStaticMarkup(
  createElement(ListFilter, { size: 14, strokeWidth: 2.25 }),
);

const TOOLTIP_TEXT = "Filter by this attribute";

// A single tooltip element portaled to <body> so CodeMirror's overflow never
// clips it (the same reason the app's React tooltips render through a portal).
// It uses fixed positioning and flips above/below to stay on-screen.
let quickFilterTooltip: HTMLElement | null = null;

const getQuickFilterTooltip = (): HTMLElement => {
  if (!quickFilterTooltip) {
    quickFilterTooltip = document.createElement("div");
    quickFilterTooltip.textContent = TOOLTIP_TEXT;
    // Matches the app's default tooltip (shadcn TooltipContent): light
    // soft-background, secondary text, border, p-2, rounded-md, text-xs,
    // shadow-md. Tokens are theme-aware, so it adapts in dark mode too.
    Object.assign(quickFilterTooltip.style, {
      position: "fixed",
      display: "none",
      zIndex: "9999",
      background: "hsl(var(--soft-background))",
      color: "hsl(var(--foreground-secondary))",
      border: "1px solid hsl(var(--border))",
      padding: "8px",
      borderRadius: "6px",
      fontSize: "12px",
      lineHeight: "1rem",
      fontFamily: "inherit",
      fontWeight: "400",
      whiteSpace: "nowrap",
      pointerEvents: "none",
      boxShadow:
        "0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -2px rgba(0, 0, 0, 0.1)",
    });
    document.body.appendChild(quickFilterTooltip);
  }
  return quickFilterTooltip;
};

const showQuickFilterTooltip = (anchor: HTMLElement) => {
  const tooltip = getQuickFilterTooltip();
  tooltip.style.display = "block";

  const anchorRect = anchor.getBoundingClientRect();
  const tooltipRect = tooltip.getBoundingClientRect();
  // Prefer above the icon; flip below only when too close to the viewport top.
  const fitsAbove = anchorRect.top > tooltipRect.height + 10;
  const top = fitsAbove
    ? anchorRect.top - tooltipRect.height - 6
    : anchorRect.bottom + 6;
  const centeredLeft =
    anchorRect.left + anchorRect.width / 2 - tooltipRect.width / 2;
  const left = Math.max(
    6,
    Math.min(centeredLeft, window.innerWidth - tooltipRect.width - 6),
  );
  tooltip.style.top = `${top}px`;
  tooltip.style.left = `${left}px`;
};

const hideQuickFilterTooltip = () => {
  if (quickFilterTooltip) quickFilterTooltip.style.display = "none";
};

// Highlights the attribute value that the hovered/focused filter icon would
// filter on, so the user sees exactly what will be filtered.
const setQuickFilterHover = StateEffect.define<{
  from: number;
  to: number;
} | null>();

const quickFilterHoverField = StateField.define<DecorationSet>({
  create: () => Decoration.none,
  update(decorations, tr) {
    decorations = decorations.map(tr.changes);
    for (const effect of tr.effects) {
      if (effect.is(setQuickFilterHover)) {
        decorations = effect.value
          ? Decoration.set([
              Decoration.mark({ class: "cm-quick-filter-target" }).range(
                effect.value.from,
                effect.value.to,
              ),
            ])
          : Decoration.none;
      }
    }
    return decorations;
  },
  provide: (field) => EditorView.decorations.from(field),
});

// Inline filter icon rendered at the end of a leaf attribute line: muted at
// rest, highlighted (with its value) on hover. Clicking applies a filter.
class QuickFilterWidget extends WidgetType {
  constructor(
    readonly from: number,
    readonly to: number,
    readonly path: string,
    readonly value: string,
    readonly onFilter: QuickFilterCodeConfig["onFilter"],
  ) {
    super();
  }

  eq(other: QuickFilterWidget) {
    return (
      other.path === this.path &&
      other.value === this.value &&
      other.from === this.from &&
      other.to === this.to
    );
  }

  toDOM(view: EditorView) {
    const button = document.createElement("span");
    button.className = "cm-quick-filter-add";
    button.setAttribute("role", "button");
    button.setAttribute("tabindex", "0");
    button.setAttribute("aria-label", "Filter by this attribute");
    button.innerHTML = FILTER_ICON;

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

    const setActive = (active: boolean) => {
      try {
        // The view may already be torn down (e.g. the panel closed while the
        // icon was hovered/focused), in which case the highlight is moot.
        view.dispatch({
          effects: setQuickFilterHover.of(
            active ? { from: this.from, to: this.to } : null,
          ),
        });
      } catch {
        // ignore dispatch on a destroyed view
      }
      if (active) showQuickFilterTooltip(button);
      else hideQuickFilterTooltip();
    };
    button.onmouseenter = () => setActive(true);
    button.onmouseleave = () => setActive(false);
    button.onfocus = () => setActive(true);
    button.onblur = () => setActive(false);

    return button;
  }

  ignoreEvent() {
    return false;
  }

  destroy() {
    // The icon may be removed while hovered/focused (viewport change) without a
    // matching mouseleave/blur; clear the shared tooltip so it can't get stuck.
    hideQuickFilterTooltip();
  }
}

const quickFilterTheme = EditorView.baseTheme({
  ".cm-quick-filter-add": {
    position: "relative",
    display: "inline-flex",
    alignItems: "center",
    verticalAlign: "middle",
    marginLeft: "6px",
    color: "hsl(var(--muted-slate))",
    cursor: "pointer",
    // Subtle at rest, revealed on line hover for discoverability.
    opacity: "0.25",
    transition: "opacity 0.1s ease-in-out, color 0.1s ease-in-out",
  },
  ".cm-line:hover .cm-quick-filter-add": {
    opacity: "0.6",
  },
  ".cm-quick-filter-add:hover, .cm-quick-filter-add:focus-visible": {
    opacity: "1",
    color: "hsl(var(--primary))",
    outline: "none",
  },
  // The value text the hovered/focused icon would filter on.
  ".cm-quick-filter-target": {
    color: "hsl(var(--primary)) !important",
  },
});

/**
 * Adds a hover filter affordance to each filterable leaf attribute in the
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
              target.from,
              target.pos,
              target.path,
              target.value,
              config.onFilter,
            ),
            // Negative side keeps the filter icon directly after the value and
            // before the fold control, so the order stays consistent whether
            // the foldable line is expanded (chevron) or collapsed (placeholder).
            side: -1,
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

      destroy() {
        // Editor torn down (panel close / unmount): drop the shared tooltip.
        hideQuickFilterTooltip();
      }
    },
    { decorations: (plugin) => plugin.decorations },
  );

  return [plugin, quickFilterTheme, quickFilterHoverField];
};
