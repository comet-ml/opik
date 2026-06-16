import React from "react";

import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { EditorState, Extension } from "@codemirror/state";
import { jsonLanguage } from "@codemirror/lang-json";
import { yamlLanguage } from "@codemirror/lang-yaml";
import { pythonLanguage } from "@codemirror/lang-python";
import { StreamLanguage } from "@codemirror/language";
import { shell } from "@codemirror/legacy-modes/mode/shell";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { useCodemirrorLineHighlight } from "@/hooks/useCodemirrorLineHighlight";
import { cn } from "@/lib/utils";
import CopyButton from "@/shared/CopyButton/CopyButton";
import { SUPPORTED_LANGUAGE } from "@/constants/codeLanguage";

export { SUPPORTED_LANGUAGE };

const PLUGINS_MAP: Record<SUPPORTED_LANGUAGE, Extension> = {
  [SUPPORTED_LANGUAGE.json]: jsonLanguage,
  [SUPPORTED_LANGUAGE.yaml]: yamlLanguage,
  [SUPPORTED_LANGUAGE.python]: pythonLanguage,
  [SUPPORTED_LANGUAGE.bash]: StreamLanguage.define(shell),
};

type CodeHighlighterProps = {
  data: string;
  copyData?: string;
  language?: SUPPORTED_LANGUAGE;
  highlightedLines?: number[];
  hideCopy?: boolean;
  /**
   * When true, the wrapper drops its `bg-primary-foreground` and the CodeMirror
   * editor uses a transparent background. Use this when the highlighter is
   * slotted inside a parent container that already provides a background
   * (e.g. `FormFieldCard`) — otherwise two backgrounds stack visibly.
   */
  transparent?: boolean;
};

const CodeHighlighter: React.FunctionComponent<CodeHighlighterProps> = ({
  data,
  copyData,
  language = SUPPORTED_LANGUAGE.python,
  highlightedLines,
  hideCopy = false,
  transparent = false,
}) => {
  const theme = useCodemirrorTheme({ transparent });
  const LineHighlightExtension = useCodemirrorLineHighlight({
    lines: highlightedLines,
  });

  return (
    <div
      className={cn(
        "relative overflow-hidden rounded-md",
        !transparent && "bg-primary-foreground",
      )}
    >
      {!hideCopy && (
        <div className="absolute right-2 top-0.5 z-10">
          <CopyButton
            message="Successfully copied code"
            text={copyData || data}
            tooltipText="Copy code"
          />
        </div>
      )}
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
