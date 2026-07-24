import { useMemo, useState, useCallback, startTransition } from "react";
import useLocalStorageState from "use-local-storage-state";
import {
  MODE_TYPE,
  UNUSED_SYNTAX_HIGHLIGHTER_KEY,
} from "@/shared/SyntaxHighlighter/constants";
import { PrettifyConfig, CodeOutput } from "@/shared/SyntaxHighlighter/types";
import {
  generateSyntaxHighlighterCode,
  generateSelectOptions,
} from "@/shared/SyntaxHighlighter/utils";

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
  defaultCollapsedIds?: string[],
) => {
  const [initialCount] = useState(allMessageIds.length);
  const defaultExpanded = initialCount <= LLM_MESSAGES_COLLAPSE_THRESHOLD;

  const [initialDefaultCollapsedIds] = useState(defaultCollapsedIds ?? []);
  const hasDefaultCollapsedOverride = initialDefaultCollapsedIds.length > 0;

  // "all expanded" means literally all -- false when an override applies,
  // even under the threshold, so the toggle button/tooltip stays truthful.
  const autoDefaultExpanded = defaultExpanded && !hasDefaultCollapsedOverride;

  const [localExplicitIsAllExpanded, setLocalExplicitIsAllExpanded] = useState<
    boolean | undefined
  >(undefined);

  // Only an explicit user action (an Expand/Collapse-all click, or manually expanding every
  // message via the accordion) is ever written here -- deliberately no `defaultValue`. This key
  // is shared across every trace in the app, not scoped per-trace, and use-local-storage-state
  // writes back whatever `defaultValue` it's given the moment the key is first read (see its
  // `getSnapshot` "store default value in localStorage" branch). Passing a per-trace-computed
  // default there would let whichever trace happens to mount first permanently bake its own
  // message-count-based default into storage for every other trace afterward -- exactly the bug
  // that made already-shown-message collapsing silently stop working after any earlier trace
  // (even a short, unrelated one) had rendered once.
  const [preservedExplicitIsAllExpanded, setPreservedExplicitIsAllExpanded] =
    useLocalStorageState<boolean | undefined>(
      preserveKey
        ? `${preserveKey}-llm-expand-all`
        : UNUSED_SYNTAX_HIGHLIGHTER_KEY,
    );

  const [explicitIsAllExpanded, setExplicitIsAllExpanded] = useMemo(
    () =>
      preserveKey
        ? [preservedExplicitIsAllExpanded, setPreservedExplicitIsAllExpanded]
        : [localExplicitIsAllExpanded, setLocalExplicitIsAllExpanded],
    [
      localExplicitIsAllExpanded,
      preserveKey,
      preservedExplicitIsAllExpanded,
      setPreservedExplicitIsAllExpanded,
    ],
  );

  // An explicit prior choice (persisted or not) always wins; absent one, fall back to this
  // trace's own freshly computed default.
  const isAllExpanded = explicitIsAllExpanded ?? autoDefaultExpanded;

  const [customExpandedIds, setCustomExpandedIds] = useState<Set<string>>(() =>
    defaultExpanded && hasDefaultCollapsedOverride
      ? new Set(
          allMessageIds.filter(
            (id) => !initialDefaultCollapsedIds.includes(id),
          ),
        )
      : new Set(),
  );

  const expandedMessages = useMemo<string[]>(() => {
    if (isAllExpanded) {
      return allMessageIds;
    }
    return allMessageIds.filter((id) => customExpandedIds.has(id));
  }, [isAllExpanded, allMessageIds, customExpandedIds]);

  const handleToggleAll = useCallback(() => {
    startTransition(() => {
      setExplicitIsAllExpanded(!isAllExpanded);
      setCustomExpandedIds(new Set());
    });
  }, [isAllExpanded, setExplicitIsAllExpanded]);

  const handleValueChange = useCallback(
    (value: string[]) => {
      const newExpandedSet = new Set(value);
      const allExpanded =
        allMessageIds.length > 0 &&
        allMessageIds.every((id) => newExpandedSet.has(id));

      setExplicitIsAllExpanded(allExpanded);
      setCustomExpandedIds(allExpanded ? new Set() : newExpandedSet);
    },
    [allMessageIds, setExplicitIsAllExpanded],
  );

  return {
    isAllExpanded,
    expandedMessages,
    handleToggleAll,
    handleValueChange,
  };
};
