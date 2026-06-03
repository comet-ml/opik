import { useMemo } from "react";
import { buildClaudeCodeData } from "./fixtures";
import { ClaudeCodeData } from "./types";

export function useClaudeCodeData(
  windowDays: 7 | 30 | 90 = 30,
): ClaudeCodeData {
  return useMemo(() => buildClaudeCodeData(windowDays), [windowDays]);
}
