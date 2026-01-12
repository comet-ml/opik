import { Extension } from "@codemirror/state";
import { EditorView, ViewUpdate } from "@codemirror/view";
import { foldService, foldState, foldEffect } from "@codemirror/language";
import { isBase64DataUrl } from "@/lib/base64";

// Minimal pattern to find potential base64 data URIs (validated with isBase64DataUrl)
// We need the regex to get position/index, but validation is done by isBase64DataUrl
const base64Pattern = /data:[^,]+base64,[A-Za-z0-9+/=]+/g;

/**
 * Finds base64 strings in a line and returns the fold range if found
 */
const findBase64FoldRange = (
  lineText: string,
  lineStart: number,
): { from: number; to: number } | null => {
  base64Pattern.lastIndex = 0; // Reset regex
  const match = base64Pattern.exec(lineText);
  if (!match || !isBase64DataUrl(match[0])) {
    return null;
  }

  const fullBase64 = match[0];
  const base64Start = lineStart + match.index;
  const base64End = lineStart + match.index + fullBase64.length;

  // Only fold if the base64 string is long enough
  if (base64End - base64Start < 100) {
    return null;
  }

  const commaIndex = fullBase64.indexOf(",");
  if (commaIndex === -1) {
    return null;
  }

  return {
    from: base64Start + commaIndex + 1,
    to: base64End,
  };
};

/**
 * Custom fold service that folds lines containing base64 strings
 */
const base64FoldService = foldService.of(({ doc }, from) => {
  const line = doc.lineAt(from);
  return findBase64FoldRange(line.text, line.from);
});

export const createBase64ExpandExtension = (): Extension => {
  let lastDocContent = "";

  const foldAllBase64 = (view: EditorView) => {
    const state = view.state;
    const foldEffects = [];
    for (let i = 1; i <= state.doc.lines; i++) {
      const line = state.doc.line(i);
      const foldRange = findBase64FoldRange(line.text, line.from);
      if (foldRange) {
        foldEffects.push(foldEffect.of(foldRange));
      }
    }
    if (foldEffects.length > 0) {
      view.dispatch({ effects: foldEffects });
    }
  };

  return [
    base64FoldService,
    foldState,
    EditorView.updateListener.of((update: ViewUpdate) => {
      // Only auto-fold when document content actually changes (not on search/decorations)
      const currentDoc = update.view.state.doc.toString();
      const isInitialLoad = lastDocContent === "";
      const hasContentChanged =
        update.docChanged && currentDoc !== lastDocContent;

      if (
        (isInitialLoad || hasContentChanged) &&
        update.view.state.field(foldState)
      ) {
        lastDocContent = currentDoc;
        foldAllBase64(update.view);
      }
    }),
  ];
};
