import { useEffect } from "react";

const PROMPT_ID_ATTRIBUTE = "data-prompt-id";
const OPEN_OVERLAY_SELECTOR =
  '[role="dialog"], [role="alertdialog"], [role="menu"], [role="listbox"]';

type UsePlaygroundRunHotkeyParams = {
  promptCount: number;
  canRunAll: boolean;
  canRunPrompt: (promptId: string) => boolean;
  onRunAll: () => void;
  onRunPrompt: (promptId: string) => void;
};

const getFocusedPromptId = (target: EventTarget | null) =>
  target instanceof Element
    ? target
        .closest(`[${PROMPT_ID_ATTRIBUTE}]`)
        ?.getAttribute(PROMPT_ID_ATTRIBUTE)
    : null;

const usePlaygroundRunHotkey = ({
  promptCount,
  canRunAll,
  canRunPrompt,
  onRunAll,
  onRunPrompt,
}: UsePlaygroundRunHotkeyParams) => {
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      const isModEnter =
        event.key === "Enter" && (event.metaKey || event.ctrlKey);
      if (!isModEnter || event.isComposing) return;
      if (document.querySelector(OPEN_OVERLAY_SELECTOR)) return;

      event.preventDefault();
      event.stopPropagation();

      const promptId =
        promptCount > 1 ? getFocusedPromptId(event.target) : null;

      if (promptId) {
        if (canRunPrompt(promptId)) onRunPrompt(promptId);
      } else if (canRunAll) {
        onRunAll();
      }
    };

    // Capture phase, so this runs before the prompt editor sees the key:
    // CodeMirror binds Mod+Enter to "insert a blank line" and would add one.
    window.addEventListener("keydown", handleKeyDown, true);
    return () => window.removeEventListener("keydown", handleKeyDown, true);
  }, [promptCount, canRunAll, canRunPrompt, onRunAll, onRunPrompt]);
};

export default usePlaygroundRunHotkey;
