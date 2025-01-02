import React from "react";
import { Settings2 } from "lucide-react";

import { PlaygroundPromptConfigsType } from "@/types/playground";
import {
  PlaygroundOpenAIConfigsType,
  PROVIDER_TYPE,
  PlaygroundAnthropicConfigsType,
} from "@/types/providers";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";

import OpenAIModelConfigs from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PromptModelSettings/providerConfigs/OpenAIModelConfigs";
import AnthropicModelConfigs from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PromptModelSettings/providerConfigs/AnthropicModelConfigs";

interface PromptModelConfigsProps {
  provider: PROVIDER_TYPE | "";
  configs: PlaygroundPromptConfigsType;
  onChange: (configs: Partial<PlaygroundPromptConfigsType>) => void;
}

const PromptModelConfigs = ({
  provider,
  configs,
  onChange,
}: PromptModelConfigsProps) => {
  const getProviderForm = () => {
    if (provider === PROVIDER_TYPE.OPEN_AI) {
      return (
        <OpenAIModelConfigs
          configs={configs as PlaygroundOpenAIConfigsType}
          onChange={onChange}
        />
      );
    }

    if (provider === PROVIDER_TYPE.ANTHROPIC) {
      return (
        <AnthropicModelConfigs
          configs={configs as PlaygroundAnthropicConfigsType}
          onChange={onChange}
        />
      );
    }

    return;
  };

  const noProvider = provider === "";

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="icon-sm" disabled={noProvider}>
          <Settings2 className="size-3.5" />
        </Button>
      </DropdownMenuTrigger>

      <DropdownMenuContent className="p-6" side="bottom" align="end">
        {getProviderForm()}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default PromptModelConfigs;
