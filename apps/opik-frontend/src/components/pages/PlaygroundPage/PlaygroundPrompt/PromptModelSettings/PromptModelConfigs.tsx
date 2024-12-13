import React from "react";
import { Settings2 } from "lucide-react";
import {
  PLAYGROUND_PROVIDER,
  PlaygroundOpenAIConfigsType,
  PlaygroundPromptConfigsType,
} from "@/types/playground";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";

import OpenAIModelConfigs from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PromptModelSettings/providerConfigs/OpenAIModelConfigs";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

interface PromptModelConfigsProps {
  provider: PLAYGROUND_PROVIDER | "";
  configs: PlaygroundPromptConfigsType;
  onChange: (configs: Partial<PlaygroundPromptConfigsType>) => void;
}

const PromptModelConfigs = ({
  provider,
  configs,
  onChange,
}: PromptModelConfigsProps) => {
  const getProviderForm = () => {
    if (provider === PLAYGROUND_PROVIDER.OpenAI) {
      return (
        <OpenAIModelConfigs
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

      <DropdownMenuContent className="p-6" side="bottom" align="end">
        {getProviderForm()}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default PromptModelConfigs;
