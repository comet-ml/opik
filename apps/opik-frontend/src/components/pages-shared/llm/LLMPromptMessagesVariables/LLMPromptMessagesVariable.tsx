import React from "react";
import { DropdownOption } from "@/types/shared";
import TracesPathsAutocomplete from "@/components/pages-shared/traces/TracesPathsAutocomplete/TracesPathsAutocomplete";
import { Tag } from "@/components/ui/tag";

interface LLMPromptMessagesVariableProps {
  variable: DropdownOption<string>;
  onChange: (changes: DropdownOption<string>) => void;
  projectId: string;
}

const LLMPromptMessagesVariable = ({
  variable,
  onChange,
  projectId,
}: LLMPromptMessagesVariableProps) => {
  return (
    <div className="relative flex justify-between">
      <div className="flex max-w-[50%] basis-1/2 items-center pr-2">
        <Tag variant="green" size="lg" className="truncate">
          {variable.label}
        </Tag>
      </div>
      <div className="flex basis-1/2">
        <div className="w-full">
          <TracesPathsAutocomplete
            projectId={projectId}
            rootKeys={["input", "output", "metadata"]}
            value={variable.value}
            onValueChange={(value: string) =>
              onChange({ ...variable, value: value })
            }
          />
        </div>
      </div>
    </div>
  );
};

export default LLMPromptMessagesVariable;
