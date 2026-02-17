import { stringify as yamlStringify } from "yaml";
import { prettifyMessage } from "@/lib/traces";
import {
  MODE_TYPE,
  DEFAULT_OPTIONS,
} from "@/components/shared/SyntaxHighlighter/constants";
import {
  PrettifyConfig,
  CodeOutput,
} from "@/components/shared/SyntaxHighlighter/types";

export const generateSyntaxHighlighterCode = (
  data: object,
  mode: MODE_TYPE,
  prettifyConfig?: PrettifyConfig,
): CodeOutput => {
  const response = prettifyConfig
    ? prettifyMessage(data, {
        type: prettifyConfig.fieldType,
      })
    : {
        message: data,
        prettified: false,
      };

  const canBePrettified = response.prettified;

  switch (mode) {
    case MODE_TYPE.yaml:
      return {
        message: yamlStringify(data).trim(),
        mode: MODE_TYPE.yaml,
        prettified: false,
        canBePrettified,
      };
    case MODE_TYPE.json:
      return {
        message: JSON.stringify(data, null, 2),
        mode: MODE_TYPE.json,
        prettified: false,
        canBePrettified,
      };
    case MODE_TYPE.pretty:
      return {
        message: response.prettified
          ? (response.message as string)
          : yamlStringify(data).trim(),
        mode: canBePrettified ? MODE_TYPE.pretty : MODE_TYPE.yaml,
        prettified: response.prettified,
        canBePrettified,
      };
    default:
      return {
        message: yamlStringify({}).trim(),
        mode: MODE_TYPE.yaml,
        prettified: false,
        canBePrettified: false,
      };
  }
};

export const generateSelectOptions = (
  prettifyConfig?: PrettifyConfig,
  canBePrettified: boolean = false,
) => {
  if (prettifyConfig) {
    return [
      {
        value: MODE_TYPE.pretty,
        label: "Pretty ✨",
        ...(!canBePrettified && {
          disabled: !canBePrettified,
          tooltip: "Pretty ✨ is not available yet for this format.",
        }),
      },
      ...DEFAULT_OPTIONS,
    ];
  }

  return DEFAULT_OPTIONS;
};

export const escapeRegexSpecialChars = (text: string): string => {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
};

export const createSearchRegex = (searchTerm: string): RegExp => {
  return new RegExp(`(${escapeRegexSpecialChars(searchTerm)})`, "gi");
};

export const scrollToMatchByIndex = (
  container: HTMLElement | null,
  matchIndex: number,
): void => {
  if (!container) return;

  const element = container.querySelector(`[data-match-index="${matchIndex}"]`);
  if (element) {
    element.scrollIntoView({ block: "center" });
  }
};
