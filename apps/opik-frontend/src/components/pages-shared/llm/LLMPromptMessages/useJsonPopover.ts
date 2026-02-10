import { useState, useCallback, useMemo, RefObject } from "react";
import { EditorView, keymap, ViewUpdate } from "@codemirror/view";
import { JsonValue } from "@/components/shared/JsonTreePopover";

const MUSTACHE_OPEN = "{{";
const MUSTACHE_CLOSE = "}}";
const MUSTACHE_OPEN_LEN = MUSTACHE_OPEN.length;
const MUSTACHE_CLOSE_LEN = MUSTACHE_CLOSE.length;
const BRACE_OPEN = "{";
const BRACE_CLOSE = "}";
const BRACKET_OPEN = "[";

interface BraceKeyHandlerDeps {
  editorViewRef: RefObject<EditorView | null>;
  isJsonPopoverOpen: boolean;
  setBraceStartPos: (pos: number | null) => void;
  setJsonSearchQuery: (query: string) => void;
  setPopoverPosition: (pos: { top: number; left: number }) => void;
  setIsJsonPopoverOpen: (open: boolean) => void;
}

/**
 * Handles the '{' key press. Inserts the character and opens the popover
 * if this completes a '{{' sequence.
 */
const handleOpenBraceKey = (deps: BraceKeyHandlerDeps): boolean => {
  const {
    editorViewRef,
    setBraceStartPos,
    setJsonSearchQuery,
    setPopoverPosition,
    setIsJsonPopoverOpen,
  } = deps;

  const view = editorViewRef.current;
  if (!view) return true;

  const selection = view.state.selection.main;
  const from = selection.from;
  const to = selection.to;
  const doc = view.state.doc;

  // Check if the character before the selection start is also BRACE_OPEN
  const charBefore = from > 0 ? doc.sliceString(from - 1, from) : "";

  // Replace selection (or insert at cursor if no selection) with BRACE_OPEN
  const newCursorPos = from + 1;
  view.dispatch({
    changes: { from, to, insert: BRACE_OPEN },
    selection: { anchor: newCursorPos },
  });

  // If this completes MUSTACHE_OPEN, open the popover
  if (charBefore === BRACE_OPEN) {
    // Store the position after MUSTACHE_OPEN
    setBraceStartPos(newCursorPos);
    setJsonSearchQuery("");

    // Use requestMeasure to get accurate coordinates after DOM update
    view.requestMeasure({
      read: () => {
        const coords = view.coordsAtPos(newCursorPos);
        const editorRect = view.dom.getBoundingClientRect();
        return { coords, editorRect };
      },
      write: ({ coords, editorRect }) => {
        if (coords && editorRect) {
          setPopoverPosition({
            top: coords.bottom - editorRect.top,
            left: coords.left - editorRect.left,
          });
        }
        setIsJsonPopoverOpen(true);
      },
    });
  }

  return true;
};

/**
 * Handles the '}' key press. Inserts the character and closes the popover
 * if this completes a '}}' sequence while the popover is open.
 */
const handleCloseBraceKey = (deps: BraceKeyHandlerDeps): boolean => {
  const {
    editorViewRef,
    isJsonPopoverOpen,
    setBraceStartPos,
    setJsonSearchQuery,
    setIsJsonPopoverOpen,
  } = deps;

  const view = editorViewRef.current;
  if (!view) return true;

  const selection = view.state.selection.main;
  const from = selection.from;
  const to = selection.to;
  const doc = view.state.doc;

  // Check if the character before the selection start is also BRACE_CLOSE
  const charBefore = from > 0 ? doc.sliceString(from - 1, from) : "";

  // Replace selection (or insert at cursor if no selection) with BRACE_CLOSE
  const newCursorPos = from + 1;
  view.dispatch({
    changes: { from, to, insert: BRACE_CLOSE },
    selection: { anchor: newCursorPos },
  });

  // If this completes MUSTACHE_CLOSE, close the popover
  if (charBefore === BRACE_CLOSE && isJsonPopoverOpen) {
    setIsJsonPopoverOpen(false);
    setJsonSearchQuery("");
    setBraceStartPos(null);
  }

  return true;
};

/**
 * Handles the '[' key press. When the popover is open, inserts the character
 * without auto-pairing. Otherwise, allows default behavior.
 */
const handleOpenBracketKey = (deps: BraceKeyHandlerDeps): boolean => {
  const { editorViewRef, isJsonPopoverOpen } = deps;

  const view = editorViewRef.current;
  if (!view) return false;

  // When popover is open, replace selection with '[' without auto-pairing
  if (isJsonPopoverOpen) {
    const selection = view.state.selection.main;
    const from = selection.from;
    const to = selection.to;
    const newCursorPos = from + 1;
    view.dispatch({
      changes: { from, to, insert: BRACKET_OPEN },
      selection: { anchor: newCursorPos },
    });
    return true;
  }

  return false;
};

/**
 * Creates the CodeMirror keymap extension for mustache delimiter handling.
 */
const createBraceKeyExtension = (deps: BraceKeyHandlerDeps) => {
  return keymap.of([
    {
      key: BRACE_OPEN,
      run: () => handleOpenBraceKey(deps),
    },
    {
      key: BRACE_CLOSE,
      run: () => handleCloseBraceKey(deps),
    },
    {
      key: BRACKET_OPEN,
      run: () => handleOpenBracketKey(deps),
    },
  ]);
};

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
        // Replace from after MUSTACHE_OPEN to current cursor with the path and closing MUSTACHE_CLOSE
        view.dispatch({
          changes: {
            from: braceStartPos,
            to: cursorPos,
            insert: `${path}${MUSTACHE_CLOSE}`,
          },
          selection: {
            anchor: braceStartPos + path.length + MUSTACHE_CLOSE_LEN,
          },
        });
        view.focus();
      } else if (view) {
        // Fallback: check if {{ already exists before cursor
        const cursorPos = view.state.selection.main.head;
        const doc = view.state.doc;
        const textBeforeCursor =
          cursorPos >= MUSTACHE_OPEN_LEN
            ? doc.sliceString(cursorPos - MUSTACHE_OPEN_LEN, cursorPos)
            : "";

        if (textBeforeCursor === MUSTACHE_OPEN) {
          // {{ exists before cursor, just insert path and closing }}
          view.dispatch({
            changes: {
              from: cursorPos,
              to: cursorPos,
              insert: `${path}${MUSTACHE_CLOSE}`,
            },
            selection: {
              anchor: cursorPos + path.length + MUSTACHE_CLOSE_LEN,
            },
          });
          view.focus();
        } else {
          // No {{ before cursor, insert full mustache syntax
          insertTextAtCursor(`${MUSTACHE_OPEN}${path}${MUSTACHE_CLOSE}`);
        }
      } else {
        // No view available, use insertTextAtCursor
        insertTextAtCursor(`${MUSTACHE_OPEN}${path}${MUSTACHE_CLOSE}`);
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

  // Track text changes and cursor moves while popover is open to update search query
  // Also detect when {{ was pasted and set braceStartPos accordingly
  const handleEditorUpdate = useCallback(
    (update: ViewUpdate) => {
      const doc = update.state.doc;
      const cursorPos = update.state.selection.main.head;

      // Detect pasted {{ when popover is open but braceStartPos is null
      if (
        isJsonPopoverOpen &&
        braceStartPos === null &&
        update.docChanged &&
        hasJsonData
      ) {
        const textBeforeCursor =
          cursorPos >= MUSTACHE_OPEN_LEN
            ? doc.sliceString(cursorPos - MUSTACHE_OPEN_LEN, cursorPos)
            : "";

        if (textBeforeCursor === MUSTACHE_OPEN) {
          // {{ was pasted, set braceStartPos to position after {{
          setBraceStartPos(cursorPos);
          return;
        }
      }

      if (
        isJsonPopoverOpen &&
        braceStartPos !== null &&
        (update.docChanged || update.selectionSet)
      ) {
        // Check if MUSTACHE_OPEN is still present before braceStartPos
        const openingBraces =
          braceStartPos >= MUSTACHE_OPEN_LEN
            ? doc.sliceString(braceStartPos - MUSTACHE_OPEN_LEN, braceStartPos)
            : "";

        // If MUSTACHE_OPEN was deleted, close the popover
        if (openingBraces !== MUSTACHE_OPEN) {
          setIsJsonPopoverOpen(false);
          setJsonSearchQuery("");
          setBraceStartPos(null);
          return;
        }

        // Extract text between MUSTACHE_OPEN and cursor
        if (cursorPos >= braceStartPos) {
          const textAfterBraces = doc.sliceString(braceStartPos, cursorPos);
          // Only update if it looks like a search query (no special chars that would close the variable)
          if (
            !textAfterBraces.includes(BRACE_CLOSE) &&
            !textAfterBraces.includes(BRACE_OPEN)
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
    [isJsonPopoverOpen, braceStartPos, hasJsonData],
  );

  // CodeMirror extension to detect mustache delimiters ({{ and }})
  const braceKeyExtension = useMemo(() => {
    if (!hasJsonData) return null;

    return createBraceKeyExtension({
      editorViewRef,
      isJsonPopoverOpen,
      setBraceStartPos,
      setJsonSearchQuery,
      setPopoverPosition,
      setIsJsonPopoverOpen,
    });
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
