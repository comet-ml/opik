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

  const cursorPos = view.state.selection.main.head;
  const doc = view.state.doc;

  // Check if the character before cursor is also BRACE_OPEN
  const charBefore =
    cursorPos > 0 ? doc.sliceString(cursorPos - 1, cursorPos) : "";

  // Insert the BRACE_OPEN character first
  view.dispatch({
    changes: { from: cursorPos, insert: BRACE_OPEN },
    selection: { anchor: cursorPos + 1 },
  });

  // If this completes MUSTACHE_OPEN, open the popover
  if (charBefore === BRACE_OPEN) {
    // Store the position after MUSTACHE_OPEN
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

  const cursorPos = view.state.selection.main.head;
  const doc = view.state.doc;

  // Check if the character before cursor is also BRACE_CLOSE
  const charBefore =
    cursorPos > 0 ? doc.sliceString(cursorPos - 1, cursorPos) : "";

  // Insert the BRACE_CLOSE character
  view.dispatch({
    changes: { from: cursorPos, insert: BRACE_CLOSE },
    selection: { anchor: cursorPos + 1 },
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

  // When popover is open, insert just '[' without auto-pairing
  if (isJsonPopoverOpen) {
    const cursorPos = view.state.selection.main.head;
    view.dispatch({
      changes: { from: cursorPos, insert: BRACKET_OPEN },
      selection: { anchor: cursorPos + 1 },
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
      } else {
        // Fallback: insert full mustache syntax at cursor
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

  // Track text changes while popover is open to update search query
  const handleEditorUpdate = useCallback(
    (update: ViewUpdate) => {
      if (isJsonPopoverOpen && braceStartPos !== null && update.docChanged) {
        const doc = update.state.doc;
        const cursorPos = update.state.selection.main.head;

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
    [isJsonPopoverOpen, braceStartPos],
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
