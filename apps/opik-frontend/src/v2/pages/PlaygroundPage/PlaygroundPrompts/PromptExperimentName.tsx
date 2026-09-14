import React from "react";
import { FlaskConical } from "lucide-react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Button, ButtonProps } from "@/ui/button";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

interface PromptExperimentNameProps {
  promptId: string;
  value?: string;
  onChange: (value: string) => void;
  size?: ButtonProps["size"];
  variant?: ButtonProps["variant"];
}

const PromptExperimentName: React.FC<PromptExperimentNameProps> = ({
  promptId,
  value = "",
  onChange,
  size = "icon-xs",
  variant = "ghost",
}) => {
  const inputId = `experiment-name-${promptId}`;

  return (
    <DropdownMenu>
      <TooltipWrapper content={value || "Experiment name"}>
        <DropdownMenuTrigger asChild>
          <Button variant={variant} size={size}>
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
