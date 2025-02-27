import React from "react";
import { Settings2 } from "lucide-react";

import {
  LLMAnthropicConfigsType,
  LLMOpenAIConfigsType,
  LLMOpenRouterConfigsType,
  LLMPromptConfigsType,
  PROVIDER_TYPE,
} from "@/types/providers";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button, ButtonProps } from "@/components/ui/button";

import OpenAIModelConfigs from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/OpenAIModelConfigs";
import AnthropicModelConfigs from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/AnthropicModelConfigs";
import isEmpty from "lodash/isEmpty";
import OpenRouterModelConfigs from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/OpenRouterModelConfigs";

interface PromptModelConfigsProps {
  provider: PROVIDER_TYPE | "";
  size?: ButtonProps["size"];
  configs: Partial<LLMPromptConfigsType>;
  onChange: (configs: Partial<LLMPromptConfigsType>) => void;
}

const PromptModelConfigs = ({
  provider,
  size = "icon-sm",
  configs,
  onChange,
}: PromptModelConfigsProps) => {
  const getProviderForm = () => {
    if (provider === PROVIDER_TYPE.OPEN_AI) {
      return (
        <OpenAIModelConfigs
          configs={configs as LLMOpenAIConfigsType}
          onChange={onChange}
        />
      );
    }

    if (provider === PROVIDER_TYPE.ANTHROPIC) {
      return (
        <AnthropicModelConfigs
          configs={configs as LLMAnthropicConfigsType}
          onChange={onChange}
        />
      );
    }

    if (provider === PROVIDER_TYPE.OPEN_ROUTER) {
      return (
        <OpenRouterModelConfigs
          configs={configs as LLMOpenRouterConfigsType}
          onChange={onChange}
        />
      );
    }

    return;
  };

  const disabled = provider === "" || isEmpty(configs);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size={size} disabled={disabled}>
          <Settings2 />
        </Button>
      </DropdownMenuTrigger>

      <DropdownMenuContent
        className="max-h-[70vh] overflow-y-auto p-6"
        side="bottom"
        align="end"
      >
        {getProviderForm()}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default PromptModelConfigs;
