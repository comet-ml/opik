import React from "react";
import CodeMirror from "@uiw/react-codemirror";
import { jsonLanguage } from "@codemirror/lang-json";
import { EditorView } from "@codemirror/view";

import { FormFieldCard } from "@/v2/pages-shared/llm/FormFieldCard";
import CodeBlockCopy from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/CodeBlock/CodeBlockCopy";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";

// Static so React doesn't reinitialize the editor on every render.
const EXTENSIONS = [jsonLanguage, EditorView.lineWrapping];

type PromptMetadataEditorProps = {
  value: string;
  onChange: (value: string) => void;
  showInvalidJSON?: boolean;
};

/**
 * Metadata JSON editor shared by the prompt Create and Edit sheets — same card
 * chrome, CodeMirror config, copy action, and validation message.
 */
const PromptMetadataEditor: React.FC<PromptMetadataEditorProps> = ({
  value,
  onChange,
  showInvalidJSON,
}) => {
  // Use a transparent CodeMirror bg so the FormFieldCard's `bg-soft-background`
  // shows through instead of stacking a second `--codemirror-background` panel.
  const theme = useCodemirrorTheme({ editable: true, transparent: true });

  return (
    <div className="space-y-1.5">
      <FormFieldCard
        title="Metadata"
        actions={<CodeBlockCopy text={value} />}
        bodyClassName="px-0 pt-2"
      >
        <div className="max-h-60 overflow-y-auto">
          <CodeMirror
            theme={theme}
            value={value}
            onChange={onChange}
            extensions={EXTENSIONS}
          />
        </div>
      </FormFieldCard>
      {showInvalidJSON && (
        <p className="comet-body-s text-destructive">
          Metadata field is not valid
        </p>
      )}
    </div>
  );
};

export default PromptMetadataEditor;
