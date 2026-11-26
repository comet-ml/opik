import { useEffect } from "react";

export const UNDO_SCOPE_ATTRIBUTE = "data-undo-scope";
export const NATIVE_UNDO_ATTRIBUTE = "data-native-undo";

type UndoRedoShortcutsOptions = {
  isEnabled: boolean;
  onUndo: () => void;
  onRedo: () => void;
};

const getCommand = (event: KeyboardEvent) => {
  if (!(event.metaKey || event.ctrlKey) || event.altKey) return null;
  const key = event.key.toLowerCase();
  if (key === "z") return event.shiftKey ? "redo" : "undo";
  if (key === "y" && !event.shiftKey) return "redo";
  return null;
};

const isTextInput = (element: Element | null): element is HTMLElement =>
  element instanceof HTMLInputElement ||
  element instanceof HTMLTextAreaElement ||
  (element instanceof HTMLElement && element.isContentEditable);

const usesNativeUndo = (element: Element | null) =>
  isTextInput(element) &&
  (!element.closest(`[${UNDO_SCOPE_ATTRIBUTE}]`) ||
    Boolean(element.closest(`[${NATIVE_UNDO_ATTRIBUTE}]`)));

const useUndoRedoShortcuts = ({
  isEnabled,
  onUndo,
  onRedo,
}: UndoRedoShortcutsOptions) => {
  useEffect(() => {
    if (!isEnabled) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      const command = getCommand(event);
      if (!command || usesNativeUndo(document.activeElement)) return;
      event.preventDefault();
      event.stopPropagation();
      if (command === "undo") onUndo();
      else onRedo();
    };
    // Capture phase so this runs before CodeMirror's own undo, which reverts
    // only the prompt text and leaves the panel history out of sync.
    window.addEventListener("keydown", handleKeyDown, { capture: true });
    return () =>
      window.removeEventListener("keydown", handleKeyDown, { capture: true });
  }, [isEnabled, onUndo, onRedo]);
};

export default useUndoRedoShortcuts;
