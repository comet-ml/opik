import React from "react";
import CodeMirror from "@/shared/CodeMirrorEditor/CodeMirrorEditor";
import { EditorView } from "@codemirror/view";
import { jsonLanguage } from "@codemirror/lang-json";
import { Info } from "lucide-react";

import PromptModelSettingsTooltipContent from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import useJsonInput from "@/hooks/useJsonInput";
import { Label } from "@/ui/label";
import { FormErrorSkeleton } from "@/ui/form";

interface ExtraBodyParametersInputProps {
  value: Record<string, unknown> | null | undefined;
  onChange: (value: Record<string, unknown> | null) => void;
  tooltip: string;
  placeholder: string;
  children?: React.ReactNode;
}

const ExtraBodyParametersInput = ({
  value,
  onChange,
  tooltip,
  placeholder,
  children,
}: ExtraBodyParametersInputProps) => {
  const theme = useCodemirrorTheme({ editable: true });
  const { jsonString, showInvalidJSON, handleJsonChange, handleJsonBlur } =
    useJsonInput({ value, onChange });

  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor="custom_parameters" className="flex items-center gap-1">
        Extra body parameters (Optional)
        <TooltipWrapper
          content={<PromptModelSettingsTooltipContent text={tooltip} />}
        >
          <Info className="ml-1 size-4 text-light-slate" />
        </TooltipWrapper>
      </Label>
      <div className="max-h-52 overflow-y-auto rounded-md border">
        <CodeMirror
          id="custom_parameters"
          theme={theme}
          value={jsonString}
          onChange={handleJsonChange}
          onBlur={handleJsonBlur}
          extensions={[jsonLanguage, EditorView.lineWrapping]}
          placeholder={placeholder}
          basicSetup={{
            lineNumbers: false,
            foldGutter: false,
            highlightActiveLine: false,
            highlightSelectionMatches: false,
          }}
        />
      </div>
      {showInvalidJSON ? (
        <FormErrorSkeleton>
          {value
            ? "Invalid JSON, not saved. Runs keep the last valid JSON."
            : "Invalid JSON, not saved."}
        </FormErrorSkeleton>
      ) : (
        children
      )}
    </div>
  );
};

export default ExtraBodyParametersInput;
