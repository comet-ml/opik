/**
 * Pure model helpers for {@link ./PromptComparison}.
 *
 * Optimization prompts are OpenAI-style message arrays. For the diff view we
 * group message content by role (system / user / assistant / …) and pair the
 * comparison target's content against the current prompt's, role by role —
 * matching the Figma trial-details diff (one card per role, content diffed
 * line by line).
 */

import {
  OpenAIMessage,
  extractMessageContent,
  extractPromptData,
} from "@/lib/prompt";

/** Roles render in this order; anything else falls after, sorted alphabetically. */
export const ROLE_DISPLAY_ORDER = ["system", "user", "assistant"];

export type RoleContent = {
  role: string;
  content: string;
};

export type RoleDiffRow = {
  role: string;
  /** Comparison-target content for this role (empty when the role was added). */
  baseContent: string;
  /** Current-prompt content for this role (empty when the role was removed). */
  currentContent: string;
};

const groupByRole = (messages: OpenAIMessage[]): Map<string, string> => {
  const map = new Map<string, string>();
  messages.forEach((msg) => {
    const existing = map.get(msg.role) ?? "";
    map.set(
      msg.role,
      existing + (existing ? "\n" : "") + extractMessageContent(msg.content),
    );
  });
  return map;
};

export const sortRoles = (roles: Iterable<string>): string[] =>
  Array.from(roles).sort((a, b) => {
    const aIndex = ROLE_DISPLAY_ORDER.indexOf(a);
    const bIndex = ROLE_DISPLAY_ORDER.indexOf(b);
    if (aIndex === -1 && bIndex === -1) return a.localeCompare(b);
    if (aIndex === -1) return 1;
    if (bIndex === -1) return -1;
    return aIndex - bIndex;
  });

const asSingleMessages = (prompt: unknown): OpenAIMessage[] | null => {
  const extracted = extractPromptData(prompt);
  return extracted?.type === "single" ? extracted.data : null;
};

/**
 * Groups a single message-array prompt into ordered {role, content} entries.
 * Returns null when the prompt is not a plain OpenAI message array (e.g. named
 * multi-prompts or raw strings), letting the caller fall back to a text view.
 */
export const groupMessageContentByRole = (
  prompt: unknown,
): RoleContent[] | null => {
  const messages = asSingleMessages(prompt);
  if (!messages) return null;

  const byRole = groupByRole(messages);
  return sortRoles(byRole.keys()).map((role) => ({
    role,
    content: byRole.get(role) ?? "",
  }));
};

/**
 * Pairs a baseline/target prompt against the current prompt, role by role, over
 * the union of roles. Returns null when either side is not a plain message
 * array, so the caller can fall back to a whole-text diff.
 */
export const buildRoleDiffRows = (
  baseline: unknown,
  current: unknown,
): RoleDiffRow[] | null => {
  const baseMessages = asSingleMessages(baseline);
  const currentMessages = asSingleMessages(current);
  if (!baseMessages || !currentMessages) return null;

  const baseByRole = groupByRole(baseMessages);
  const currentByRole = groupByRole(currentMessages);
  const roles = sortRoles(
    new Set([...baseByRole.keys(), ...currentByRole.keys()]),
  );

  return roles.map((role) => ({
    role,
    baseContent: baseByRole.get(role) ?? "",
    currentContent: currentByRole.get(role) ?? "",
  }));
};
