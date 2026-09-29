import { Document, Scalar, visit } from "yaml";
import { prettifyMessage } from "@/lib/traces";
import {
  MODE_TYPE,
  DEFAULT_OPTIONS,
} from "@/shared/SyntaxHighlighter/constants";
import { PrettifyConfig, CodeOutput } from "@/shared/SyntaxHighlighter/types";

// lineWidth 0 disables folding so long values stay on one line.
// version 1.1 quotes yes/no/on/off and dates, so copied YAML keeps its
// strings when loaded by 1.1 parsers such as PyYAML.
const YAML_OPTIONS = { lineWidth: 0, version: "1.1" } as const;

// A "\r" forces double-quoted style, collapsing Windows-authored text onto one line.
const normalizeLineEndings = (_key: unknown, value: unknown) =>
  typeof value === "string" ? value.replace(/\r\n/g, "\n") : value;

// yaml double-quotes any string containing DEL or a C1 control but emits the
// character raw, which PyYAML rejects (DEL) or reads as a line break (NEL).
// Double quotes are the only place they can appear, so escaping is always valid.
const escapeDelAndC1 = (yaml: string) =>
  yaml.replace(/[\x7f-\x9f]/g, (char) =>
    char === "\x85"
      ? "\\N"
      : `\\x${char.charCodeAt(0).toString(16).toUpperCase()}`,
  );

// "=" and "<<" are the 1.1 value and merge keys, which yaml leaves plain and
// PyYAML then refuses to load.
const YAML_11_VALUES_REQUIRING_QUOTES = new Set(["=", "<<"]);

// 1.1 has no 0o octal syntax, so yaml leaves "0o17" plain, but 1.2 parsers
// such as js-yaml read it as a number.
const YAML_12_OCTAL = /^[-+]?0o[0-7]+$/;

const needsQuotes = (value: unknown) =>
  typeof value === "string" &&
  (YAML_11_VALUES_REQUIRING_QUOTES.has(value) || YAML_12_OCTAL.test(value));

const toYaml = (data: object): string => {
  // A trace with no output passes undefined, which would otherwise print "null".
  if (data === undefined) return "";

  const doc = new Document(data, normalizeLineEndings, YAML_OPTIONS);
  visit(doc, {
    Scalar(_key, node) {
      if (needsQuotes(node.value)) {
        node.type = Scalar.QUOTE_SINGLE;
      }
    },
  });

  return escapeDelAndC1(doc.toString(YAML_OPTIONS)).trim();
};

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
        message: toYaml(data),
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
          : toYaml(data),
        mode: canBePrettified ? MODE_TYPE.pretty : MODE_TYPE.yaml,
        prettified: response.prettified,
        canBePrettified,
      };
    default:
      return {
        message: toYaml({}),
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
