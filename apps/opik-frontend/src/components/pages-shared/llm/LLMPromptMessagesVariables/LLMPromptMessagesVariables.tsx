import React, { useCallback, useMemo } from "react";
import { DropdownOption } from "@/types/shared";
import LLMPromptMessagesVariable from "@/components/pages-shared/llm/LLMPromptMessagesVariables/LLMPromptMessagesVariable";

interface LLMPromptMessagesVariablesProps {
  variables: Record<string, string>;
  onChange: (variables: Record<string, string>) => void;
}

const LLMPromptMessagesVariables = ({
  variables,
  onChange,
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
      <div className="comet-body-s mb-2 text-light-slate">
        {`Variables are added based on the prompt, all variables as {{variable 1}} will be added to this list.`}
      </div>
      <div className="flex flex-col gap-2 overflow-hidden">
        {variablesList.map((variable) => (
          <LLMPromptMessagesVariable
            key={variable.label}
            onChange={(changes) => handleChangeVariables(changes)}
            variable={variable}
          />
        ))}
      </div>
    </div>
  );
};

export default LLMPromptMessagesVariables;
