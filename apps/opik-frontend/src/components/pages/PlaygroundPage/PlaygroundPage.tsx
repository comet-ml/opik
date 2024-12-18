import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { Loader, Plus } from "lucide-react";
import PlaygroundPrompt from "./PlaygroundPrompt/PlaygroundPrompt";
import { PlaygroundPromptType } from "@/types/playground";
import { generateRandomString } from "@/lib/utils";
import {
  generateDefaultPlaygroundPromptMessage,
  getDefaultConfigByProvider,
  getModelProvider,
} from "@/lib/playground";
import PlaygroundOutputs from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputs";
import useAppStore from "@/store/AppStore";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import first from "lodash/first";
import { PROVIDERS } from "@/constants/providers";
import { PROVIDER_TYPE } from "@/types/providers";

interface GenerateDefaultPromptParams {
  initPrompt?: Partial<PlaygroundPromptType>;
  setupProviders?: PROVIDER_TYPE[];
}

const generateDefaultPrompt = ({
  initPrompt = {},
  setupProviders = [],
}: GenerateDefaultPromptParams): PlaygroundPromptType => {
  const defaultProviderKey = first(setupProviders);
  const defaultModel = defaultProviderKey
    ? PROVIDERS[defaultProviderKey].defaultModel
    : "";

  return {
    name: "Prompt",
    messages: [generateDefaultPlaygroundPromptMessage()],
    model: defaultModel,
    configs: defaultProviderKey
      ? getDefaultConfigByProvider(defaultProviderKey)
      : {},
    ...initPrompt,
    id: generateRandomString(),
  };
};

const PlaygroundPage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: providerKeysData, isPending } = useProviderKeys({
    workspaceName,
  });

  const providerKeys = useMemo(() => {
    return providerKeysData?.content?.map((c) => c.provider) || [];
  }, [providerKeysData]);

  const [prompts, setPrompts] = useState<PlaygroundPromptType[]>([]);

  const handlePromptChange = useCallback(
    (id: string, changes: Partial<PlaygroundPromptType>) => {
      setPrompts((ps) => {
        return ps.map((prompt) => {
          if (prompt.id !== id) {
            return prompt;
          }

          const result = {
            ...prompt,
            ...changes,
          };

          if (changes.model) {
            const previousProvider = prompt.model
              ? getModelProvider(prompt.model)
              : "";

            const newProvider = changes.model
              ? getModelProvider(changes.model)
              : "";

            // if a provider is changed, we need to change configs to default of a new provider
            if (newProvider !== previousProvider) {
              result.configs = newProvider
                ? getDefaultConfigByProvider(newProvider)
                : {};
            }
          }

          return result;
        });
      });
    },
    [],
  );

  const handlePromptRemove = useCallback((id: string) => {
    setPrompts((ps) => {
      return ps.filter((p) => p.id !== id);
    });
  }, []);

  const handlePromptDuplicate = useCallback(
    (prompt: PlaygroundPromptType, position: number) => {
      setPrompts((ps) => {
        const newPrompt = generateDefaultPrompt({ initPrompt: prompt });

        const newPrompts = [...ps];

        newPrompts.splice(position, 0, newPrompt);

        return newPrompts;
      });
    },
    [],
  );

  const handleAddPrompt = () => {
    const newPrompt = generateDefaultPrompt({ setupProviders: providerKeys });
    setPrompts((ps) => [...ps, newPrompt]);
  };

  useEffect(() => {
    // hasn't been initialized yet
    if (prompts.length === 0 && !isPending) {
      setPrompts([generateDefaultPrompt({ setupProviders: providerKeys })]);
    }
  }, [prompts, providerKeys, isPending]);

  if (isPending) {
    return <Loader />;
  }

  return (
    <div
      className="flex h-full w-fit min-w-full flex-col pt-6"
      style={
        {
          "--min-prompt-width": "540px",
          "--item-gap": "1.5rem",
        } as React.CSSProperties
      }
    >
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l">Playground</h1>

        <Button
          variant="outline"
          size="sm"
          onClick={handleAddPrompt}
          className="sticky right-0"
        >
          <Plus className="mr-2 size-4" />
          Add prompt
        </Button>
      </div>

      <div className="mb-6 flex min-h-[50%] w-full gap-[var(--item-gap)]">
        {prompts.map((prompt, idx) => (
          <PlaygroundPrompt
            index={idx}
            name={prompt.name}
            id={prompt.id}
            key={prompt.id}
            configs={prompt.configs}
            messages={prompt.messages}
            model={prompt.model}
            onChange={handlePromptChange}
            onClickRemove={handlePromptRemove}
            onClickDuplicate={handlePromptDuplicate}
            hideRemoveButton={prompts.length === 1}
            workspaceName={workspaceName}
          />
        ))}
      </div>

      <PlaygroundOutputs prompts={prompts} />
    </div>
  );
};

export default PlaygroundPage;
