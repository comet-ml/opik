import React from "react";

import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { EditorState, Extension } from "@codemirror/state";
import { jsonLanguage } from "@codemirror/lang-json";
import { yamlLanguage } from "@codemirror/lang-yaml";
import { pythonLanguage } from "@codemirror/lang-python";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { useCodemirrorLineHighlight } from "@/hooks/useCodemirrorLineHighlight";
import CopyButton from "@/components/shared/CopyButton/CopyButton";

export enum SUPPORTED_LANGUAGE {
  json = "json",
  yaml = "yaml",
  python = "python",
}

const PLUGINS_MAP: Record<SUPPORTED_LANGUAGE, Extension> = {
  [SUPPORTED_LANGUAGE.json]: jsonLanguage,
  [SUPPORTED_LANGUAGE.yaml]: yamlLanguage,
  [SUPPORTED_LANGUAGE.python]: pythonLanguage,
};

type CodeHighlighterProps = {
  data: string;
  copyData?: string;
  language?: SUPPORTED_LANGUAGE;
  highlightedLines?: number[];
};

const CodeHighlighter: React.FunctionComponent<CodeHighlighterProps> = ({
  data,
  copyData,
  language = SUPPORTED_LANGUAGE.python,
  highlightedLines,
}) => {
  const theme = useCodemirrorTheme();
  const LineHighlightExtension = useCodemirrorLineHighlight({
    lines: highlightedLines,
  });

  return (
    <div className="relative overflow-hidden rounded-md bg-primary-foreground">
      <div className="absolute right-2 top-0.5 z-10">
        <CopyButton
          message="Successfully copied code"
          text={copyData || data}
          tooltipText="Copy code"
        />
      </div>
      <CodeMirror
        theme={theme}
        value={data}
        extensions={[
          PLUGINS_MAP[language],
          EditorView.lineWrapping,
          EditorState.readOnly.of(true),
          EditorView.editable.of(false),
          LineHighlightExtension,
        ]}
      />
    </div>
  );
};

export default CodeHighlighter;
