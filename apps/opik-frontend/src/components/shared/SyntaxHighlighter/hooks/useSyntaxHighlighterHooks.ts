import { useMemo, useState, useCallback } from "react";
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
  return useMemo(() => {
    return generateSyntaxHighlighterCode(data, mode, prettifyConfig);
  }, [mode, data, prettifyConfig]);
};

export const useSyntaxHighlighterOptions = (
  prettifyConfig?: PrettifyConfig,
  canBePrettified: boolean = false,
) => {
  return useMemo(() => {
    return generateSelectOptions(prettifyConfig, canBePrettified);
  }, [prettifyConfig, canBePrettified]);
};

export const useLLMMessagesExpandAll = (
  allMessageIds: string[],
  preserveKey?: string,
) => {
  const [localIsAllExpanded, setLocalIsAllExpanded] = useState<boolean>(true);

  const [preservedIsAllExpanded, setPreservedIsAllExpanded] =
    useLocalStorageState(
      preserveKey
        ? `${preserveKey}-llm-expand-all`
        : UNUSED_SYNTAX_HIGHLIGHTER_KEY,
      { defaultValue: true },
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

  // Local state for custom expanded messages (when not in expand/collapse all mode)
  const [customExpandedIds, setCustomExpandedIds] = useState<Set<string>>(
    new Set(),
  );

  // Derive expanded messages based on isAllExpanded flag and custom state
  const expandedMessages = useMemo<string[]>(() => {
    if (isAllExpanded) {
      // When expand all is active, all current messages are expanded
      return allMessageIds;
    }
    // Otherwise, use custom expanded IDs filtered to current message IDs
    return allMessageIds.filter((id) => customExpandedIds.has(id));
  }, [isAllExpanded, allMessageIds, customExpandedIds]);

  // Toggle expand/collapse all
  const handleToggleAll = useCallback(() => {
    const newIsAllExpanded = !isAllExpanded;
    setIsAllExpanded(newIsAllExpanded);
    // Clear custom state when entering expand/collapse all mode
    setCustomExpandedIds(new Set());
  }, [isAllExpanded, setIsAllExpanded]);

  // Handle accordion value change and sync boolean flag
  const handleValueChange = useCallback(
    (value: string[]) => {
      // Convert array to Set for efficient lookup
      const newExpandedSet = new Set(value);

      // Check if all messages are expanded
      const allExpanded =
        allMessageIds.length > 0 &&
        allMessageIds.every((id) => newExpandedSet.has(id));

      if (allExpanded) {
        // All messages are expanded - enter expand all mode
        setIsAllExpanded(true);
        setCustomExpandedIds(new Set());
      } else {
        // Not all messages are expanded - enter custom mode
        setIsAllExpanded(false);
        setCustomExpandedIds(newExpandedSet);
      }
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
