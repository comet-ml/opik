import React from "react";
import { Settings2 } from "lucide-react";

import {
  LLMOpenAIConfigsType,
  LLMPromptConfigsType,
  PROVIDER_TYPE,
} from "@/types/providers";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";

import OpenAIModelConfigs from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/OpenAIModelConfigs";

interface PromptModelConfigsProps {
  provider: PROVIDER_TYPE | "";
  size?: "icon" | "icon-sm" | "icon-lg" | "icon-xs" | "icon-xxs";
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

    return;
  };

  const noProvider = provider === "";

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size={size} disabled={noProvider}>
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
