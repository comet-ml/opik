import React from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { EditorState } from "@codemirror/state";
import { jsonLanguage } from "@codemirror/lang-json";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { cn } from "@/lib/utils";
import CopyButton from "@/components/shared/CopyButton/CopyButton";
import { PrettyLLMMessageCodeBlockProps } from "./types";

const PRETTY_CODEMIRROR_SETUP = {
  foldGutter: false,
  lineNumbers: true,
  highlightActiveLineGutter: false,
  highlightActiveLine: false,
};

const CODEMIRROR_EXTENSIONS = [
  jsonLanguage,
  EditorView.lineWrapping,
  EditorState.readOnly.of(true),
  EditorView.editable.of(false),
];

const PrettyLLMMessageCodeBlock: React.FC<PrettyLLMMessageCodeBlockProps> = ({
  code,
  label = "JSON",
  className,
}) => {
  const theme = useCodemirrorTheme();

  return (
    <div
      className={cn(
        "overflow-hidden rounded-md border border-border bg-primary-foreground",
        className,
      )}
    >
      <div className="flex items-center justify-between border-b border-border px-3 py-0.5">
        <div className="text-xs text-muted-foreground">{label}</div>
        <CopyButton
          text={code}
          message="Code copied to clipboard"
          tooltipText="Copy code"
          size="icon-2xs"
          className="p-0 "
        />
      </div>
      <div className="[&>div>.absolute]:!hidden [&_.cm-editor]:!bg-primary-foreground [&_.cm-gutters]:!bg-primary-foreground">
        <CodeMirror
          theme={theme}
          value={code}
          basicSetup={PRETTY_CODEMIRROR_SETUP}
          extensions={CODEMIRROR_EXTENSIONS}
        />
      </div>
    </div>
  );
};

export default PrettyLLMMessageCodeBlock;
