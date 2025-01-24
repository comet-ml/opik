import React from "react";
import { DropdownOption } from "@/types/shared";
import TracesPathsAutocomplete, {
  TRACE_AUTOCOMPLETE_ROOT_KEY,
} from "@/components/pages-shared/traces/TracesPathsAutocomplete/TracesPathsAutocomplete";
import { Tag } from "@/components/ui/tag";
import { FormErrorSkeleton } from "@/components/ui/form";

const ROOT_KEYS: TRACE_AUTOCOMPLETE_ROOT_KEY[] = [
  "input",
  "output",
  "metadata",
];

interface LLMPromptMessagesVariableProps {
  variable: DropdownOption<string>;
  errorText?: string;
  onChange: (changes: DropdownOption<string>) => void;
  projectId: string;
}

const LLMPromptMessagesVariable = ({
  variable,
  errorText,
  onChange,
  projectId,
}: LLMPromptMessagesVariableProps) => {
  return (
    <div className="relative flex justify-between">
      <div className="flex max-h-10 max-w-[50%] basis-1/2 items-center pr-2">
        <Tag variant="green" size="lg" className="truncate">
          {variable.label}
        </Tag>
      </div>
      <div className="flex basis-1/2">
        <div className="w-full">
          <TracesPathsAutocomplete
            projectId={projectId}
            rootKeys={ROOT_KEYS}
            value={variable.value}
            hasError={Boolean(errorText)}
            onValueChange={(value: string) =>
              onChange({ ...variable, value: value })
            }
          />
          {errorText && <FormErrorSkeleton>{errorText}</FormErrorSkeleton>}
        </div>
      </div>
    </div>
  );
};

export default LLMPromptMessagesVariable;
