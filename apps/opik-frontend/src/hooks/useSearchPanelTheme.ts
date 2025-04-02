import { EditorView } from "@codemirror/view";
import { useMemo } from "react";

export const useSearchPanelTheme = () => {
  return useMemo(() => {
    return EditorView.theme({
      "&.cm-focused": { outline: "none !important" }, // Remove focus outline
      ".cm-cursor": { opacity: "0" }, // Hide cursor when not editable
      ".cm-activeLine": {
        backgroundColor: "transparent",
      }, // Active line highlight

      // Flattened styles for the search panel
      ".cm-panels": {
        backgroundColor: "hsl(var(--primary-foreground))",
      },
    });
  }, []);
};
