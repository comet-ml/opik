import React from "react";
import { FlaskConical } from "lucide-react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

interface PromptExperimentNameProps {
  promptId: string;
  value?: string;
  onChange: (value: string) => void;
}

const PromptExperimentName: React.FC<PromptExperimentNameProps> = ({
  promptId,
  value = "",
  onChange,
}) => {
  const inputId = `experiment-name-${promptId}`;
  const hasName = Boolean(value.trim());

  return (
    <DropdownMenu>
      <TooltipWrapper content={value || "Experiment name"}>
        <DropdownMenuTrigger asChild>
          <Button
            variant="ghost"
            size="icon-xs"
            badge={hasName}
            data-testid="playground-experiment-name-button"
          >
            <FlaskConical />
          </Button>
        </DropdownMenuTrigger>
      </TooltipWrapper>

      <DropdownMenuContent
        className="flex w-72 flex-col gap-2 p-4"
        side="bottom"
        align="start"
        onCloseAutoFocus={(e) => e.preventDefault()}
      >
        <Label htmlFor={inputId}>Experiment name</Label>
        <Input
          id={inputId}
          data-testid="playground-experiment-name-input"
          value={value}
          placeholder="Auto-generated name"
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={(e) => e.stopPropagation()}
          autoFocus
        />
        <p className="comet-body-xs text-light-slate">
          Leave blank to use an auto-generated name.
        </p>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default PromptExperimentName;
