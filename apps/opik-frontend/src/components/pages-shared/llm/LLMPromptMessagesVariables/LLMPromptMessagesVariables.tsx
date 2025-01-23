import React, { useCallback, useMemo } from "react";
import { DropdownOption } from "@/types/shared";
import { Alert, AlertTitle } from "@/components/ui/alert";
import LLMPromptMessagesVariable from "@/components/pages-shared/llm/LLMPromptMessagesVariables/LLMPromptMessagesVariable";

const DEFAULT_DESCRIPTION =
  "Variables are added based on the prompt, all variables as {{variable 1}} will be added to this list.";

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
}

const LLMPromptMessagesVariables = ({
  parsingError,
  validationErrors,
  variables,
  onChange,
  projectId,
  description = DEFAULT_DESCRIPTION,
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
      <div className="comet-body-s-accented mb-1 text-muted-slate">
        Variable mapping ({variablesList.length})
      </div>
      <div className="comet-body-s mb-2 text-light-slate">{description}</div>
      {parsingError && (
        <Alert variant="destructive">
          <AlertTitle>
            Template parsing error. The variables cannot be extracted.
          </AlertTitle>
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
