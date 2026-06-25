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
import { Check, ListFilter } from "lucide-react";
import {
  QuickFilterMode,
  collectQuickFilterTargets,
} from "@/shared/SyntaxHighlighter/quickFilterPaths";

// The extension only ever resolves a string value (unquoted editor text), so
// the callback advertises string rather than the broader JsonValue.
export type QuickFilterCodeConfig = {
  canFilter: (path: string) => boolean;
  onFilter: (path: string, value: string) => void;
};

const FILTER_ICON = renderToStaticMarkup(
  createElement(ListFilter, { size: 14, strokeWidth: 2.25 }),
);

const CHECK_ICON = renderToStaticMarkup(
  createElement(Check, { size: 12, strokeWidth: 2.5 }),
);

const TOOLTIP_TEXT = "Filter by this attribute";
const TOOLTIP_APPLIED_TEXT = "Filter applied";
// How long the "Filter applied" confirmation stays up before reverting.
const APPLIED_VISIBLE_MS = 1500;

// A tooltip portaled to <body> so CodeMirror's overflow can't clip it. One
// instance per editor, owned by the ViewPlugin (created in its constructor,
// removed in destroy()), so there's no shared module node to leak or cross-wire
// between the input/output/metadata editors mounted side by side. It serves
// both the hover hint and the post-click "Filter applied" confirmation.
class QuickFilterTooltip {
  private readonly el: HTMLElement;
  private readonly icon: HTMLElement;
  private readonly text: HTMLElement;
  private appliedTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.el = document.createElement("div");
    // Mirrors the app's default shadcn TooltipContent; tokens are theme-aware.
    Object.assign(this.el.style, {
      position: "fixed",
      display: "none",
      alignItems: "center",
      gap: "2px",
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

    this.icon = document.createElement("span");
    this.icon.innerHTML = CHECK_ICON;
    Object.assign(this.icon.style, {
      display: "none",
      alignItems: "center",
      color: "hsl(var(--success))",
    });

    this.text = document.createElement("span");
    this.text.textContent = TOOLTIP_TEXT;

    this.el.append(this.icon, this.text);
    document.body.appendChild(this.el);
  }

  private position(anchor: HTMLElement) {
    const anchorRect = anchor.getBoundingClientRect();
    const rect = this.el.getBoundingClientRect();
    // Prefer above the icon; flip below only when too close to the viewport top.
    const fitsAbove = anchorRect.top > rect.height + 10;
    const top = fitsAbove
      ? anchorRect.top - rect.height - 6
      : anchorRect.bottom + 6;
    const centeredLeft =
      anchorRect.left + anchorRect.width / 2 - rect.width / 2;
    const left = Math.max(
      6,
      Math.min(centeredLeft, window.innerWidth - rect.width - 6),
    );
    this.el.style.top = `${top}px`;
    this.el.style.left = `${left}px`;
  }

  showHint(anchor: HTMLElement) {
    this.clearTimer();
    this.icon.style.display = "none";
    this.text.textContent = TOOLTIP_TEXT;
    this.el.style.display = "flex";
    this.position(anchor);
  }

  showApplied(anchor: HTMLElement) {
    this.icon.style.display = "inline-flex";
    this.text.textContent = TOOLTIP_APPLIED_TEXT;
    this.el.style.display = "flex";
    this.position(anchor);
    // Auto-dismiss on a timer, independent of the triggering widget's DOM
    // lifecycle, so the confirmation survives a decoration rebuild.
    this.clearTimer();
    this.appliedTimer = setTimeout(() => this.hide(), APPLIED_VISIBLE_MS);
  }

  hide() {
    this.clearTimer();
    this.el.style.display = "none";
    this.icon.style.display = "none";
    this.text.textContent = TOOLTIP_TEXT;
  }

  destroy() {
    this.clearTimer();
    this.el.remove();
  }

  private clearTimer() {
    if (this.appliedTimer !== null) {
      clearTimeout(this.appliedTimer);
      this.appliedTimer = null;
    }
  }
}

// Per-editor tooltip lookup so a widget can reach its own editor's tooltip from
// an event handler (the plugin owns creation/teardown).
const quickFilterTooltips = new WeakMap<EditorView, QuickFilterTooltip>();

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
      quickFilterTooltips.get(view)?.showApplied(button);
    };
    button.onmousedown = activate;
    button.onkeydown = (event) => {
      if (event.key === "Enter" || event.key === " ") activate(event);
    };

    const setActive = (active: boolean) => {
      try {
        view.dispatch({
          effects: setQuickFilterHover.of(
            active ? { from: this.from, to: this.to } : null,
          ),
        });
      } catch {
        // View already torn down; the highlight is moot.
      }
      const tooltip = quickFilterTooltips.get(view);
      if (active) tooltip?.showHint(button);
      else tooltip?.hide();
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
      private readonly view: EditorView;
      private readonly tooltip: QuickFilterTooltip;

      constructor(view: EditorView) {
        this.view = view;
        this.tooltip = new QuickFilterTooltip();
        quickFilterTooltips.set(view, this.tooltip);
        this.decorations = build(view);
      }

      update(update: ViewUpdate) {
        if (update.docChanged || update.viewportChanged) {
          this.decorations = build(update.view);
        }
      }

      destroy() {
        quickFilterTooltips.delete(this.view);
        this.tooltip.destroy();
      }
    },
    { decorations: (plugin) => plugin.decorations },
  );

  return [plugin, quickFilterTheme, quickFilterHoverField];
};
