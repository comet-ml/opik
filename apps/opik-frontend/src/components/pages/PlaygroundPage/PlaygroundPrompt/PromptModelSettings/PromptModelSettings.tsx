import React from "react";
import { Button } from "@/components/ui/button";
import { Settings2 } from "lucide-react";
import { PLAYGROUND_PROVIDERS_TYPES } from "@/types/playgroundPrompts";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

import OpenAIModelSettings from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PromptModelSettings/providerSettings/OpenAIModelSettings";

interface PromptModelSettingsProps {
  provider: PLAYGROUND_PROVIDERS_TYPES | "";
}

// ALEX ADD KEY TO THE CONFIGURATION

const PromptModelSettings = ({ provider }: PromptModelSettingsProps) => {
  const getProviderForm = () => {
    if (provider === PLAYGROUND_PROVIDERS_TYPES.OpenAI) {
      return <OpenAIModelSettings />;
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

      <DropdownMenuContent className="p-6" side="bottom">
        {getProviderForm()}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default PromptModelSettings;
