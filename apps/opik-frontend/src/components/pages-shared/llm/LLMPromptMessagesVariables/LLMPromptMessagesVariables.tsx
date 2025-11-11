import React, { useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";

import { DropdownOption } from "@/types/shared";
import { Alert, AlertTitle } from "@/components/ui/alert";
import LLMPromptMessagesVariable from "@/components/pages-shared/llm/LLMPromptMessagesVariables/LLMPromptMessagesVariable";
import { Description } from "@/components/ui/description";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINERS_MAP, EXPLAINER_ID } from "@/constants/explainers";

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
}

const LLMPromptMessagesVariables = ({
  parsingError,
  validationErrors,
  variables,
  onChange,
  projectId,
  description,
  errorText,
  projectName,
  datasetColumnNames,
}: LLMPromptMessagesVariablesProps) => {
  const { t } = useTranslation();
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

  const displayDescription = description || t("onlineEvaluation.dialog.variableMappingDesc");
  const displayErrorText = errorText || t("onlineEvaluation.dialog.templateParsingError");

  return (
    <div className="pt-4">
      <div className="comet-body-s-accented mb-1 flex items-center gap-1 text-muted-slate">
        <span>{t("onlineEvaluation.dialog.variableMapping")} ({variablesList.length})</span>
        <ExplainerIcon
          {...EXPLAINERS_MAP[EXPLAINER_ID.llm_judge_variable_mapping]}
        />
      </div>
      <Description className="mb-2 inline-block">{displayDescription}</Description>
      {parsingError && (
        <Alert variant="destructive">
          <AlertTitle>{displayErrorText}</AlertTitle>
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
          />
        ))}
      </div>
    </div>
  );
};

export default LLMPromptMessagesVariables;
