import { modifierKey, isMac } from "@/lib/utils";

export enum SME_ACTION {
  PREVIOUS = "previous",
  NEXT = "next",
  DONE = "done",
  FOCUS_COMMENT = "focus_comment",
  BLUR_COMMENT = "blur_comment",
  FOCUS_FEEDBACK_SCORES = "focus_feedback_scores",
}

export const SME_HOTKEYS = {
  [SME_ACTION.PREVIOUS]: {
    key: "p",
    display: "P",
    description: "Go to previous item",
  },
  [SME_ACTION.NEXT]: {
    key: "n",
    display: "N",
    description: "Go to next item",
  },
  [SME_ACTION.DONE]: {
    key: `${modifierKey}+enter`,
    display: isMac ? "⌘+⏎" : "Ctrl+⏎",
    description: "Submit and continue",
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
