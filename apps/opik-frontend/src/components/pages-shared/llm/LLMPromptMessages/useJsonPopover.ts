import { useState, useCallback, useMemo, RefObject } from "react";
import { EditorView, keymap, ViewUpdate } from "@codemirror/view";
import { JsonValue } from "@/components/shared/JsonTreePopover";

interface UseJsonPopoverProps {
  editorViewRef: RefObject<EditorView | null>;
  hasJsonData: boolean;
  insertTextAtCursor: (text: string) => void;
  onJsonPathSelect?: (path: string, value: JsonValue) => void;
}

interface UseJsonPopoverReturn {
  isJsonPopoverOpen: boolean;
  jsonSearchQuery: string;
  popoverPosition: { top: number; left: number };
  handleJsonPathSelect: (path: string, value: JsonValue) => void;
  handlePopoverOpenChange: (open: boolean) => void;
  handleEditorUpdate: (update: ViewUpdate) => void;
  braceKeyExtension: ReturnType<typeof keymap.of> | null;
}

export const useJsonPopover = ({
  editorViewRef,
  hasJsonData,
  insertTextAtCursor,
  onJsonPathSelect,
}: UseJsonPopoverProps): UseJsonPopoverReturn => {
  const [isJsonPopoverOpen, setIsJsonPopoverOpen] = useState(false);
  const [jsonSearchQuery, setJsonSearchQuery] = useState("");
  const [braceStartPos, setBraceStartPos] = useState<number | null>(null);
  const [popoverPosition, setPopoverPosition] = useState({ top: 0, left: 0 });

  const handleJsonPathSelect = useCallback(
    (path: string, value: JsonValue) => {
      const view = editorViewRef.current;
      if (view && braceStartPos !== null) {
        const cursorPos = view.state.selection.main.head;
        // Replace from after '{{' to current cursor with the path and closing '}}'
        // braceStartPos is right after '{{', so we insert 'path}}'
        view.dispatch({
          changes: {
            from: braceStartPos,
            to: cursorPos,
            insert: `${path}}}`,
          },
          selection: { anchor: braceStartPos + path.length + 2 }, // Position after }}
        });
        view.focus();
      } else {
        insertTextAtCursor(`{{${path}}}`);
      }
      setIsJsonPopoverOpen(false);
      setJsonSearchQuery("");
      setBraceStartPos(null);
      onJsonPathSelect?.(path, value);
    },
    [braceStartPos, insertTextAtCursor, onJsonPathSelect, editorViewRef],
  );

  const handlePopoverOpenChange = useCallback((open: boolean) => {
    setIsJsonPopoverOpen(open);
    if (!open) {
      setJsonSearchQuery("");
      setBraceStartPos(null);
    }
  }, []);

  // Track text changes while popover is open to update search query
  const handleEditorUpdate = useCallback(
    (update: ViewUpdate) => {
      if (isJsonPopoverOpen && braceStartPos !== null && update.docChanged) {
        const doc = update.state.doc;
        const cursorPos = update.state.selection.main.head;

        // Check if '{{' is still present before braceStartPos
        // braceStartPos is right after '{{', so we check the 2 chars before it
        const openingBraces =
          braceStartPos >= 2
            ? doc.sliceString(braceStartPos - 2, braceStartPos)
            : "";

        // If '{{' was deleted, close the popover
        if (openingBraces !== "{{") {
          setIsJsonPopoverOpen(false);
          setJsonSearchQuery("");
          setBraceStartPos(null);
          return;
        }

        // Extract text between '{{' and cursor (braceStartPos is right after '{{')
        if (cursorPos >= braceStartPos) {
          const textAfterBraces = doc.sliceString(braceStartPos, cursorPos);
          // Only update if it looks like a search query (no special chars that would close the variable)
          if (
            !textAfterBraces.includes("}") &&
            !textAfterBraces.includes("{")
          ) {
            setJsonSearchQuery(textAfterBraces);
          } else {
            // If user typed } or {, close the popover
            setIsJsonPopoverOpen(false);
            setJsonSearchQuery("");
            setBraceStartPos(null);
          }
        } else {
          // Cursor moved before braceStartPos, close popover
          setIsJsonPopoverOpen(false);
          setJsonSearchQuery("");
          setBraceStartPos(null);
        }
      }
    },
    [isJsonPopoverOpen, braceStartPos],
  );

  // CodeMirror extension to detect '{{' and '}}', '[[' and ']]' sequences
  const braceKeyExtension = useMemo(() => {
    if (!hasJsonData) return null;

    return keymap.of([
      {
        key: "{",
        run: () => {
          const view = editorViewRef.current;
          if (view) {
            const cursorPos = view.state.selection.main.head;
            const doc = view.state.doc;

            // Check if the character before cursor is also '{'
            const charBefore =
              cursorPos > 0 ? doc.sliceString(cursorPos - 1, cursorPos) : "";

            // Insert the '{' character first
            view.dispatch({
              changes: { from: cursorPos, insert: "{" },
              selection: { anchor: cursorPos + 1 },
            });

            // If this is the second '{', open the popover
            if (charBefore === "{") {
              // Store the position after the opening '{{' (cursorPos was before the second {, now cursor is at cursorPos + 1)
              setBraceStartPos(cursorPos + 1);
              setJsonSearchQuery("");

              // Use requestMeasure to get accurate coordinates after DOM update
              view.requestMeasure({
                read: () => {
                  const coords = view.coordsAtPos(cursorPos + 1);
                  const editorRect = view.dom.getBoundingClientRect();
                  return { coords, editorRect };
                },
                write: ({ coords, editorRect }) => {
                  if (coords && editorRect) {
                    // Calculate position relative to the editor container
                    setPopoverPosition({
                      top: coords.bottom - editorRect.top,
                      left: coords.left - editorRect.left,
                    });
                  }
                  setIsJsonPopoverOpen(true);
                },
              });
            }
          }
          return true;
        },
      },
      {
        key: "}",
        run: () => {
          const view = editorViewRef.current;
          if (view) {
            const cursorPos = view.state.selection.main.head;
            const doc = view.state.doc;

            // Check if the character before cursor is also '}'
            const charBefore =
              cursorPos > 0 ? doc.sliceString(cursorPos - 1, cursorPos) : "";

            // Insert the '}' character
            view.dispatch({
              changes: { from: cursorPos, insert: "}" },
              selection: { anchor: cursorPos + 1 },
            });

            // If this is the second '}', close the popover
            if (charBefore === "}" && isJsonPopoverOpen) {
              setIsJsonPopoverOpen(false);
              setJsonSearchQuery("");
              setBraceStartPos(null);
            }
          }
          return true;
        },
      },
      {
        key: "[",
        run: () => {
          const view = editorViewRef.current;
          if (view) {
            const cursorPos = view.state.selection.main.head;

            // When popover is open, insert just '[' without auto-pairing
            if (isJsonPopoverOpen) {
              view.dispatch({
                changes: { from: cursorPos, insert: "[" },
                selection: { anchor: cursorPos + 1 },
              });
              return true;
            }
          }
          return false;
        },
      },
    ]);
  }, [hasJsonData, isJsonPopoverOpen, editorViewRef]);

  return {
    isJsonPopoverOpen,
    jsonSearchQuery,
    popoverPosition,
    handleJsonPathSelect,
    handlePopoverOpenChange,
    handleEditorUpdate,
    braceKeyExtension,
  };
};
