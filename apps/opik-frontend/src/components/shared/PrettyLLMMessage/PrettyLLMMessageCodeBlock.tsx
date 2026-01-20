import React from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { EditorState } from "@codemirror/state";
import { jsonLanguage } from "@codemirror/lang-json";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { useCopyToClipboard } from "@/hooks/useCopyToClipboard";
import { cn } from "@/lib/utils";
import { Check, Copy } from "lucide-react";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
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
  const { copyToClipboard, showSuccessIcon, isCopying } = useCopyToClipboard({
    successMessage: "Code copied to clipboard",
  });

  return (
    <div
      className={cn(
        "overflow-hidden rounded-md border border-border bg-primary-foreground py-1",
        className,
      )}
    >
      <div className="flex items-center justify-between border-b border-border px-3 py-1.5">
        <div className="text-xs text-muted-foreground">{label}</div>
        <TooltipWrapper content={showSuccessIcon ? "Copied!" : "Copy code"}>
          <button
            onClick={() => copyToClipboard(code)}
            disabled={isCopying}
            className="flex size-6 shrink-0 items-center justify-center rounded transition-colors hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
            tabIndex={-1}
            aria-label="Copy code"
          >
            {showSuccessIcon ? (
              <Check className="size-3 text-green-500" />
            ) : (
              <Copy className="size-3 text-light-slate" />
            )}
          </button>
        </TooltipWrapper>
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
