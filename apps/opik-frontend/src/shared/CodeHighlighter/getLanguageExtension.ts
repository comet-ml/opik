import { Extension } from "@codemirror/state";
import { jsonLanguage } from "@codemirror/lang-json";
import { yamlLanguage } from "@codemirror/lang-yaml";
import { pythonLanguage } from "@codemirror/lang-python";
import { StreamLanguage } from "@codemirror/language";
import { shell } from "@codemirror/legacy-modes/mode/shell";
import { SUPPORTED_LANGUAGE } from "@/constants/codeLanguage";

const LANGUAGE_EXTENSION_MAP: Record<SUPPORTED_LANGUAGE, Extension> = {
  [SUPPORTED_LANGUAGE.json]: jsonLanguage,
  [SUPPORTED_LANGUAGE.yaml]: yamlLanguage,
  [SUPPORTED_LANGUAGE.python]: pythonLanguage,
  [SUPPORTED_LANGUAGE.bash]: StreamLanguage.define(shell),
};

export const getLanguageExtension = (language: SUPPORTED_LANGUAGE): Extension =>
  LANGUAGE_EXTENSION_MAP[language];
