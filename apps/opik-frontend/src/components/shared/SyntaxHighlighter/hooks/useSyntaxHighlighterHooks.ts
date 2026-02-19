import { useMemo, useState, useCallback, startTransition } from "react";
import useLocalStorageState from "use-local-storage-state";
import {
  MODE_TYPE,
  UNUSED_SYNTAX_HIGHLIGHTER_KEY,
} from "@/components/shared/SyntaxHighlighter/constants";
import {
  PrettifyConfig,
  CodeOutput,
} from "@/components/shared/SyntaxHighlighter/types";
import {
  generateSyntaxHighlighterCode,
  generateSelectOptions,
} from "@/components/shared/SyntaxHighlighter/utils";

export const useSyntaxHighlighterMode = (
  prettifyConfig?: PrettifyConfig,
  preserveKey?: string,
) => {
  const defaultMode = prettifyConfig ? MODE_TYPE.pretty : MODE_TYPE.yaml;
  const [localMode, setLocalMode] = useState(defaultMode);

  const [preservedMode, setPreservedMode] = useLocalStorageState(
    preserveKey ?? UNUSED_SYNTAX_HIGHLIGHTER_KEY,
    {
      defaultValue: defaultMode,
    },
  );

  const [mode, setMode] = useMemo(
    () =>
      preserveKey
        ? [preservedMode, setPreservedMode]
        : [localMode, setLocalMode],
    [localMode, preserveKey, preservedMode, setPreservedMode],
  );

  return { mode, setMode };
};

export const useSyntaxHighlighterCode = (
  data: object,
  mode: MODE_TYPE,
  prettifyConfig?: PrettifyConfig,
): CodeOutput => {
  return useMemo(
    () => generateSyntaxHighlighterCode(data, mode, prettifyConfig),
    [mode, data, prettifyConfig],
  );
};

export const useSyntaxHighlighterOptions = (
  prettifyConfig?: PrettifyConfig,
  canBePrettified: boolean = false,
) => {
  return useMemo(
    () => generateSelectOptions(prettifyConfig, canBePrettified),
    [prettifyConfig, canBePrettified],
  );
};

const LLM_MESSAGES_COLLAPSE_THRESHOLD = 10;

export const useLLMMessagesExpandAll = (
  allMessageIds: string[],
  preserveKey?: string,
) => {
  const [initialCount] = useState(allMessageIds.length);
  const defaultExpanded = initialCount <= LLM_MESSAGES_COLLAPSE_THRESHOLD;

  const [localIsAllExpanded, setLocalIsAllExpanded] =
    useState<boolean>(defaultExpanded);

  const [preservedIsAllExpanded, setPreservedIsAllExpanded] =
    useLocalStorageState(
      preserveKey
        ? `${preserveKey}-llm-expand-all`
        : UNUSED_SYNTAX_HIGHLIGHTER_KEY,
      { defaultValue: defaultExpanded },
    );

  const [isAllExpanded, setIsAllExpanded] = useMemo(
    () =>
      preserveKey
        ? [preservedIsAllExpanded, setPreservedIsAllExpanded]
        : [localIsAllExpanded, setLocalIsAllExpanded],
    [
      localIsAllExpanded,
      preserveKey,
      preservedIsAllExpanded,
      setPreservedIsAllExpanded,
    ],
  );

  const [customExpandedIds, setCustomExpandedIds] = useState<Set<string>>(
    new Set(),
  );

  const expandedMessages = useMemo<string[]>(() => {
    if (isAllExpanded) {
      return allMessageIds;
    }
    return allMessageIds.filter((id) => customExpandedIds.has(id));
  }, [isAllExpanded, allMessageIds, customExpandedIds]);

  const handleToggleAll = useCallback(() => {
    startTransition(() => {
      setIsAllExpanded(!isAllExpanded);
      setCustomExpandedIds(new Set());
    });
  }, [isAllExpanded, setIsAllExpanded]);

  const handleValueChange = useCallback(
    (value: string[]) => {
      const newExpandedSet = new Set(value);
      const allExpanded =
        allMessageIds.length > 0 &&
        allMessageIds.every((id) => newExpandedSet.has(id));

      setIsAllExpanded(allExpanded);
      setCustomExpandedIds(allExpanded ? new Set() : newExpandedSet);
    },
    [allMessageIds, setIsAllExpanded],
  );

  return {
    isAllExpanded,
    expandedMessages,
    handleToggleAll,
    handleValueChange,
  };
};
