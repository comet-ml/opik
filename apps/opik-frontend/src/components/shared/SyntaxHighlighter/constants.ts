import { LRLanguage } from "@codemirror/language";
import { jsonLanguage } from "@codemirror/lang-json";
import { yamlLanguage } from "@codemirror/lang-yaml";

export enum MODE_TYPE {
  yaml = "yaml",
  json = "json",
  pretty = "pretty",
}

export const DEFAULT_OPTIONS = [
  { value: MODE_TYPE.yaml, label: "YAML" },
  { value: MODE_TYPE.json, label: "JSON" },
];

export const EXTENSION_MAP: { [key in MODE_TYPE]: LRLanguage | null } = {
  [MODE_TYPE.yaml]: yamlLanguage,
  [MODE_TYPE.json]: jsonLanguage,
  [MODE_TYPE.pretty]: null,
};

export const UNUSED_SYNTAX_HIGHLIGHTER_KEY =
  "__unused_syntax_highlighter_key__";

export const SEARCH_HIGHLIGHT_COLOR = "bg-[var(--search-highlight)]";
export const SEARCH_CURRENT_HIGHLIGHT_COLOR =
  "bg-[var(--search-current-highlight)]";
