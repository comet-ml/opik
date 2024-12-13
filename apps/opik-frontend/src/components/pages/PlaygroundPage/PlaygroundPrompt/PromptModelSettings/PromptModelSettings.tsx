import React from "react";
import { Button } from "@/components/ui/button";
import { Settings2 } from "lucide-react";
import {
  PLAYGROUND_PROVIDERS_TYPES,
  PlaygroundOpenAIConfigsType,
  PlaygroundPromptConfigsType,
} from "@/types/playgroundPrompts";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

import OpenAIModelSettings from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PromptModelSettings/providerSettings/OpenAIModelSettings";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

interface PromptModelSettingsProps {
  provider: PLAYGROUND_PROVIDERS_TYPES | "";
  configs: PlaygroundPromptConfigsType;
  onChange: (configs: Partial<PlaygroundPromptConfigsType>) => void;
}

// ALEX ADD KEY TO THE CONFIGURATION

const PromptModelSettings = ({
  provider,
  configs,
  onChange,
}: PromptModelSettingsProps) => {
  const getProviderForm = () => {
    if (provider === PLAYGROUND_PROVIDERS_TYPES.OpenAI) {
      return (
        <OpenAIModelSettings
          configs={configs as PlaygroundOpenAIConfigsType}
          onChange={onChange}
        />
      );
    }

    return;
  };

  const noProvider = provider === "";

  return (
    <DropdownMenu>
      <TooltipWrapper content="Model parameters">
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="icon-sm" disabled={noProvider}>
            <Settings2 className="size-3.5" />
          </Button>
        </DropdownMenuTrigger>
      </TooltipWrapper>

      <DropdownMenuContent className="p-6" side="bottom">
        {getProviderForm()}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default PromptModelSettings;
