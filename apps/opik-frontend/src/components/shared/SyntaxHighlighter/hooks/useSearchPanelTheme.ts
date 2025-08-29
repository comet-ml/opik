import { EditorView } from "@codemirror/view";
import { useMemo } from "react";

export const useSearchPanelTheme = () => {
  return useMemo(() => {
    return EditorView.theme({
      "&.cm-focused": { outline: "none !important" },
      ".cm-cursor": { opacity: "0" },
      ".cm-activeLine": {
        backgroundColor: "transparent",
      },
      ".cm-searchMatch": {
        background: "var(--search-highlight)",
      },
      ".cm-searchMatch.cm-searchMatch-selected": {
        background: "var(--search-current-highlight)",
      },
      "& .cm-panels": {
        backgroundColor: "hsl(var(--primary-foreground))",
        border: "none",
      },
    });
  }, []);
};
