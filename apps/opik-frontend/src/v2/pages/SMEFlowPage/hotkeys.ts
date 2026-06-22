import { modifierKey, isMac } from "@/lib/utils";

export enum SME_ACTION {
  NEXT_DEFAULT = "next_default",
  FOCUS_COMMENT = "focus_comment",
  BLUR_COMMENT = "blur_comment",
  FOCUS_FEEDBACK_SCORES = "focus_feedback_scores",
}

export const SME_HOTKEYS = {
  [SME_ACTION.NEXT_DEFAULT]: {
    key: `${modifierKey}+enter`,
    display: isMac ? "⌘+⏎" : "Ctrl+⏎",
    description: "Go to next item to review",
  },
  [SME_ACTION.FOCUS_COMMENT]: {
    key: "c",
    display: "C",
    description: "Focus comment textarea",
  },
  [SME_ACTION.BLUR_COMMENT]: {
    key: "escape",
    display: "Esc",
    description: "Blur comment textarea",
  },
  [SME_ACTION.FOCUS_FEEDBACK_SCORES]: {
    key: "f",
    display: "F",
    description: "Focus first feedback score input",
  },
} as const;
