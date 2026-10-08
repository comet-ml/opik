import { ViewPlugin, ViewUpdate } from "@uiw/react-codemirror";
import {
  Decoration,
  DecorationSet,
  EditorView,
  WidgetType,
} from "@codemirror/view";
import {
  Compartment,
  EditorSelection,
  EditorState,
  Facet,
} from "@codemirror/state";

// Backspace leaves the cursor facing the text before it. In an empty editor
// that is the 1em-tall widget buffer in front of the placeholder, and
// @codemirror/view 6.28 sizes the cursor from it, so the cursor shrinks
// below the text height. Facing forward sizes it from the placeholder text.
const keepEmptyEditorCursorFullHeight = EditorState.transactionFilter.of(
  (tr) => {
    const { main } = tr.newSelection;
    if (tr.newDoc.length > 0 || !main.empty || main.assoc >= 0) return tr;
    return [
      tr,
      {
        selection: EditorSelection.create([EditorSelection.cursor(0, 1)]),
        sequential: true,
      },
    ];
  },
);

const promptEditorStyles = EditorView.theme({
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
  // With no line padding, CodeMirror's default -0.6px cursor margin puts a
  // column-0 cursor outside .cm-scroller, which clips it: an empty editor
  // shows no cursor at all.
  ".cm-cursor": {
    marginLeft: 0,
  },
  ".cm-scroller": {
    fontFamily: "inherit",
  },
  ".cm-placeholder": {
    color: "var(--codemirror-gutter)",
    fontWeight: 300,
  },
  ".comet-variable-hint": {
    color: "var(--color-green)",
    background: "color-mix(in srgb, var(--color-green) 12%, transparent)",
    opacity: "0.6",
    borderRadius: "0 4px 4px 0",
    padding: "1px 5px 1px 0",
    pointerEvents: "none",
  },
  ".comet-variable-brace": {
    color: "var(--color-green)",
    background: "color-mix(in srgb, var(--color-green) 12%, transparent)",
    borderRadius: "4px 0 0 4px",
    padding: "1px 2px 1px 5px",
  },
});

export const codeMirrorPromptTheme = [
  promptEditorStyles,
  keepEmptyEditorCursorFullHeight,
];

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

// --- Variable hint inline widget ---

export interface VariableHintConfig {
  text: string | null;
  pos: number | null;
}

const variableHintFacet = Facet.define<VariableHintConfig, VariableHintConfig>({
  combine: (values) => values[values.length - 1] ?? { text: null, pos: null },
});

class VariableHintWidget extends WidgetType {
  constructor(readonly text: string) {
    super();
  }
  toDOM() {
    const span = document.createElement("span");
    span.textContent = this.text;
    span.className = "comet-variable-hint";
    return span;
  }
  ignoreEvent() {
    return true;
  }
}

const braceMark = Decoration.mark({ class: "comet-variable-brace" });

const variableHintPlugin = ViewPlugin.fromClass(
  class {
    decorations: DecorationSet;

    constructor(view: EditorView) {
      this.decorations = this.buildDecorations(view);
    }

    update(update: ViewUpdate) {
      this.decorations = this.buildDecorations(update.view);
    }

    buildDecorations(view: EditorView): DecorationSet {
      const { text, pos: rawPos } = view.state.facet(variableHintFacet);
      if (!text) return Decoration.none;
      const pos = rawPos ?? view.state.selection.main.head;
      if (pos < 0 || pos > view.state.doc.length) return Decoration.none;
      const braceStart = pos - 2;
      const decorations = [];
      if (braceStart >= 0) {
        decorations.push(braceMark.range(braceStart, pos));
      }
      decorations.push(
        Decoration.widget({
          widget: new VariableHintWidget(text),
          side: 1,
        }).range(pos),
      );
      return Decoration.set(decorations);
    }
  },
  {
    decorations: (v) => v.decorations,
  },
);

export class VariableHintExtension {
  private compartment = new Compartment();

  getExtension() {
    return [
      this.compartment.of(variableHintFacet.of({ text: null, pos: null })),
      variableHintPlugin,
    ];
  }

  update(view: EditorView, config: VariableHintConfig) {
    view.dispatch({
      effects: this.compartment.reconfigure(variableHintFacet.of(config)),
    });
  }
}
