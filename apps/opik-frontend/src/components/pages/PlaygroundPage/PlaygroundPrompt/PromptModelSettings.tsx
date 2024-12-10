import React from "react";
import { Button } from "@/components/ui/button";
import { Settings2 } from "lucide-react";
import { PLAYGROUND_MODEL_TYPE } from "@/types/playgroundPrompts";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

interface PromptModelSettingsProps {
  model: PLAYGROUND_MODEL_TYPE | "";
}

const PromptModelSettings = ({ model }: PromptModelSettingsProps) => {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="icon-sm" onClick={() => {}}>
          <Settings2 className="size-3.5" />
        </Button>
      </DropdownMenuTrigger>

      <DropdownMenuContent>POM</DropdownMenuContent>
    </DropdownMenu>
  );
};

export default PromptModelSettings;
