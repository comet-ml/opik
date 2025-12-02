import React from "react";
import { Settings2 } from "lucide-react";
import isEmpty from "lodash/isEmpty";

import {
  LLMAnthropicConfigsType,
  LLMGeminiConfigsType,
  LLMOpenAIConfigsType,
  LLMOpenRouterConfigsType,
  LLMPromptConfigsType,
  LLMVertexAIConfigsType,
  LLMCustomConfigsType,
  PROVIDER_TYPE,
  PROVIDER_MODEL_TYPE,
  COMPOSED_PROVIDER_TYPE,
} from "@/types/providers";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button, ButtonProps } from "@/components/ui/button";

import OpenAIModelConfigs from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/OpenAIModelConfigs";
import AnthropicModelConfigs from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/AnthropicModelConfigs";
import OpenRouterModelConfigs from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/OpenRouterModelConfigs";
import GeminiModelConfigs from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/GeminiModelConfigs";
import VertexAIModelConfigs from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/VertexAIModelConfigs";
import CustomModelConfigs from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/CustomModelConfig";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { parseComposedProviderType } from "@/lib/provider";

interface PromptModelConfigsProps {
  provider: COMPOSED_PROVIDER_TYPE;
  model?: PROVIDER_MODEL_TYPE | "";
  size?: ButtonProps["size"];
  configs: Partial<LLMPromptConfigsType>;
  onChange: (configs: Partial<LLMPromptConfigsType>) => void;
  disabled?: boolean;
}

const PromptModelConfigs = ({
  provider: composedProviderType,
  model,
  size = "icon-sm",
  configs,
  onChange,
  disabled: disabledProp = false,
}: PromptModelConfigsProps) => {
  const provider: PROVIDER_TYPE =
    parseComposedProviderType(composedProviderType);

  const getProviderForm = () => {
    if (provider === PROVIDER_TYPE.OPEN_AI) {
      return (
        <OpenAIModelConfigs
          configs={configs as LLMOpenAIConfigsType}
          model={model}
          onChange={onChange}
        />
      );
    }

    if (provider === PROVIDER_TYPE.ANTHROPIC) {
      return (
        <AnthropicModelConfigs
          configs={configs as LLMAnthropicConfigsType}
          onChange={onChange}
          model={model}
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

    if (provider === PROVIDER_TYPE.GEMINI) {
      return (
        <GeminiModelConfigs
          configs={configs as LLMGeminiConfigsType}
          model={model}
          onChange={onChange}
        />
      );
    }

    if (provider === PROVIDER_TYPE.VERTEX_AI) {
      return (
        <VertexAIModelConfigs
          configs={configs as LLMVertexAIConfigsType}
          model={model}
          onChange={onChange}
        />
      );
    }

    if (provider === PROVIDER_TYPE.CUSTOM) {
      return (
        <CustomModelConfigs
          configs={configs as LLMCustomConfigsType}
          onChange={onChange}
        />
      );
    }

    return;
  };

  const disabled = disabledProp || !composedProviderType || isEmpty(configs);

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
        <ExplainerDescription
          className="mb-5 w-72"
          {...EXPLAINERS_MAP[EXPLAINER_ID.whats_these_configuration_things]}
        />
        {getProviderForm()}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default PromptModelConfigs;
