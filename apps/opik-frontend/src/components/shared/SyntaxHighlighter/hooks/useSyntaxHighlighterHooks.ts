import { useMemo, useState } from "react";
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
