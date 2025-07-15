import { EditorView } from "@codemirror/view";
import { useMemo } from "react";
import {
  SEARCH_CURRENT_HIGHLIGHT_COLOR,
  SEARCH_HIGHLIGHT_COLOR,
} from "../constants";

export const useSearchPanelTheme = () => {
  return useMemo(() => {
    return EditorView.theme({
      "&.cm-focused": { outline: "none !important" }, // Remove focus outline
      ".cm-cursor": { opacity: "0" }, // Hide cursor when not editable
      ".cm-activeLine": {
        backgroundColor: "transparent",
      }, // Active line highlight
      ".cm-searchMatch": {
        backgroundColor: SEARCH_HIGHLIGHT_COLOR,
      },
      ".cm-searchMatch.cm-searchMatch-selected": {
        backgroundColor: SEARCH_CURRENT_HIGHLIGHT_COLOR,
      },

      // Flattened styles for the search panel
      "& .cm-panels": {
        backgroundColor: "hsl(var(--primary-foreground))",
        border: "none",
      },
    });
  }, []);
};
