import React, { useCallback, useMemo } from "react";

import { DropdownOption } from "@/types/shared";
import { Alert, AlertTitle } from "@/components/ui/alert";
import LLMPromptMessagesVariable from "@/components/pages-shared/llm/LLMPromptMessagesVariables/LLMPromptMessagesVariable";
import { Description } from "@/components/ui/description";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINERS_MAP, EXPLAINER_ID } from "@/constants/explainers";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";

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
  projectName?: string;
  datasetColumnNames?: string[];
  type?: TRACE_DATA_TYPE;
  includeIntermediateNodes?: boolean;
}

const LLMPromptMessagesVariables = ({
  parsingError,
  validationErrors,
  variables,
  onChange,
  projectId,
  description = DEFAULT_DESCRIPTION,
  errorText = DEFAULT_ERROR_TEXT,
  projectName,
  datasetColumnNames,
  type = TRACE_DATA_TYPE.traces,
  includeIntermediateNodes = false,
}: LLMPromptMessagesVariablesProps) => {
  const variablesList: DropdownOption<string>[] = useMemo(() => {
    if (!variables || typeof variables !== "object") {
      return [];
    }
    return Object.entries(variables)
      .map((e) => ({ label: e[0], value: e[1] }))
      .sort((a, b) => a.label.localeCompare(b.label));
  }, [variables]);

  const handleChangeVariables = useCallback(
    (changes: DropdownOption<string>) => {
      const safeVariables =
        variables && typeof variables === "object" ? variables : {};
      onChange({ ...safeVariables, [changes.label]: changes.value });
    },
    [onChange, variables],
  );

  return (
    <div className="pt-4">
      <div className="comet-body-s-accented mb-1 flex items-center gap-1 text-muted-slate">
        <span>Variable mapping ({variablesList.length})</span>
        <ExplainerIcon
          {...EXPLAINERS_MAP[EXPLAINER_ID.llm_judge_variable_mapping]}
        />
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
            projectName={projectName}
            datasetColumnNames={datasetColumnNames}
            type={type}
            includeIntermediateNodes={includeIntermediateNodes}
          />
        ))}
      </div>
    </div>
  );
};

export default LLMPromptMessagesVariables;
