import React from "react";
import { DropdownOption } from "@/types/shared";
import DebounceInput from "@/components/shared/DebounceInput/DebounceInput";
import { Tag } from "@/components/ui/tag";

interface LLMPromptMessagesVariableProps {
  variable: DropdownOption<string>;
  onChange: (changes: DropdownOption<string>) => void;
}

const LLMPromptMessagesVariable = ({
  variable,
  onChange,
}: LLMPromptMessagesVariableProps) => {
  return (
    <div className="relative flex justify-between">
      <div className="flex max-w-[50%] basis-1/2 items-center pr-2">
        <Tag variant="green" size="lg" className="truncate">
          {variable.label}
        </Tag>
      </div>
      <div className="flex basis-1/2">
        <DebounceInput
          placeholder="Set variable mapping"
          value={variable.value}
          onChangeValue={(value) =>
            onChange({ ...variable, value: value as string })
          }
        />
      </div>
    </div>
  );
};

export default LLMPromptMessagesVariable;
