import React, { useCallback, useMemo } from "react";
import { Info } from "lucide-react";

import { DropdownOption } from "@/types/shared";
import { Alert, AlertTitle } from "@/components/ui/alert";
import LLMPromptMessagesVariable from "@/components/pages-shared/llm/LLMPromptMessagesVariables/LLMPromptMessagesVariable";
import { Description } from "@/components/ui/description";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const DEFAULT_DESCRIPTION =
  "Detected variables in your prompt (e.g., {{variable1}}) will appear below. For each one, select a field from a recent trace to map it — including image fields like input.image_url or output.image_base64. These mappings auto-fill the variables during rule execution.";

const DEFAULT_ERROR_TEXT =
  "Template parsing error. The variables cannot be extracted.";

interface MessageVariablesValidationError {
  [key: string]: {
    message: string;
  };
}

interface LLMPromptMessagesVariablesProps {
  parsingError?: boolean;
  validationErrors?: MessageVariablesValidationError;
  variables: Record<string, string>;
  onChange: (variables: Record<string, string>) => void;
  projectId: string;
  description?: string;
  errorText?: string;
}

const LLMPromptMessagesVariables = ({
  parsingError,
  validationErrors,
  variables,
  onChange,
  projectId,
  description = DEFAULT_DESCRIPTION,
  errorText = DEFAULT_ERROR_TEXT,
}: LLMPromptMessagesVariablesProps) => {
  const variablesList: DropdownOption<string>[] = useMemo(() => {
    return Object.entries(variables)
      .map((e) => ({ label: e[0], value: e[1] }))
      .sort((a, b) => a.label.localeCompare(b.label));
  }, [variables]);

  const handleChangeVariables = useCallback(
    (changes: DropdownOption<string>) => {
      onChange({ ...variables, [changes.label]: changes.value });
    },
    [onChange, variables],
  );

  return (
    <div className="pt-4">
      <div className="comet-body-s-accented mb-1 flex items-center gap-1 text-muted-slate">
        <span>Variable mapping ({variablesList.length})</span>
        <TooltipWrapper content="Choose the trace field that should fill each variable. Map variables to any trace field, including image fields like input.image_url or output.image_base64.">
          <Info className="size-3.5 text-light-slate" />
        </TooltipWrapper>
      </div>
      <Description className="mb-2 inline-block">{description}</Description>
      {parsingError && (
        <Alert variant="destructive">
          <AlertTitle>{errorText}</AlertTitle>
        </Alert>
      )}
      <div className="flex flex-col gap-2 overflow-hidden">
        {variablesList.map((variable) => (
          <LLMPromptMessagesVariable
            key={variable.label}
            variable={variable}
            errorText={validationErrors?.[variable.label]?.message}
            onChange={(changes) => handleChangeVariables(changes)}
            projectId={projectId}
          />
        ))}
      </div>
    </div>
  );
};

export default LLMPromptMessagesVariables;
