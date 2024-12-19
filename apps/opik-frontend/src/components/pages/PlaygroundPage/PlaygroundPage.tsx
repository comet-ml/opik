import React, { useCallback, useEffect, useMemo, useRef } from "react";
import { Button } from "@/components/ui/button";
import { Loader, Plus, RotateCcw } from "lucide-react";
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
import { PROVIDERS } from "@/constants/providers";
import { PROVIDER_TYPE } from "@/types/providers";
import useLocalStorageState from "use-local-storage-state";
import { getDefaultProviderKey } from "@/lib/provider";

interface GenerateDefaultPromptParams {
  initPrompt?: Partial<PlaygroundPromptType>;
  setupProviders?: PROVIDER_TYPE[];
}

const generateDefaultPrompt = ({
  initPrompt = {},
  setupProviders = [],
}: GenerateDefaultPromptParams): PlaygroundPromptType => {
  const defaultProviderKey = getDefaultProviderKey(setupProviders);
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
    output: null,
    id: generateRandomString(),
  };
};

const PLAYGROUND_PROMPTS_STATE_KEY = "playground-prompts-state";
const PlaygroundPage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const checkedIfModelsAreValidRef = useRef(false);

  const { data: providerKeysData, isPending: isPendingProviderKeys } =
    useProviderKeys({
      workspaceName,
    });

  const providerKeys = useMemo(() => {
    return providerKeysData?.content?.map((c) => c.provider) || [];
  }, [providerKeysData]);

  const [prompts, setPrompts] = useLocalStorageState<PlaygroundPromptType[]>(
    PLAYGROUND_PROMPTS_STATE_KEY,
    {
      defaultValue: [],
    },
  );

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
    [setPrompts],
  );

  const handlePromptRemove = useCallback(
    (id: string) => {
      setPrompts((ps) => {
        return ps.filter((p) => p.id !== id);
      });
    },
    [setPrompts],
  );

  const handlePromptDuplicate = useCallback(
    (prompt: PlaygroundPromptType, position: number) => {
      setPrompts((ps) => {
        const newPrompt = generateDefaultPrompt({ initPrompt: prompt });
        const newPrompts = [...ps];

        newPrompts.splice(position, 0, newPrompt);

        return newPrompts;
      });
    },
    [setPrompts],
  );

  const handleAddPrompt = () => {
    const newPrompt = generateDefaultPrompt({ setupProviders: providerKeys });
    setPrompts((ps) => [...ps, newPrompt]);
  };

  const resetPlayground = useCallback(() => {
    const newPrompt = generateDefaultPrompt({ setupProviders: providerKeys });
    setPrompts(() => [newPrompt]);
  }, [providerKeys, setPrompts]);

  useEffect(() => {
    // hasn't been initialized yet or the last prompt is removed
    if (prompts?.length === 0 && !isPendingProviderKeys) {
      resetPlayground();
    }
  }, [prompts, isPendingProviderKeys, resetPlayground]);

  useEffect(() => {
    // on init, to check if all prompts have models from valid providers: (f.e., remove a provider after setting a model)
    if (
      !checkedIfModelsAreValidRef.current &&
      prompts.length !== 0 &&
      !isPendingProviderKeys
    ) {
      setPrompts((ps) => {
        return ps.map((p) => {
          const modelProvider = p.model ? getModelProvider(p.model) : "";

          const noModelProviderWhenProviderKeysSet =
            !modelProvider && providerKeys.length > 0;
          const modelProviderIsNotFromProviderKeys =
            modelProvider && !providerKeys.includes(modelProvider);

          const needToChangeProvider =
            noModelProviderWhenProviderKeysSet ||
            modelProviderIsNotFromProviderKeys;

          if (!needToChangeProvider) {
            return p;
          }

          const newProvider = getDefaultProviderKey(providerKeys);
          const newModel = newProvider
            ? PROVIDERS[newProvider].defaultModel
            : "";

          const newDefaultConfigs = newProvider
            ? getDefaultConfigByProvider(newProvider)
            : {};

          return {
            ...p,
            model: newModel,
            output: "",
            configs: newDefaultConfigs,
          };
        });
      });

      checkedIfModelsAreValidRef.current = true;
    }
  }, [prompts, providerKeys, isPendingProviderKeys, setPrompts]);

  if (isPendingProviderKeys) {
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

        <div className="sticky right-0 flex gap-2 ">
          <Button variant="outline" size="sm" onClick={resetPlayground}>
            <RotateCcw className="mr-2 size-4" />
            Reset playground
          </Button>

          <Button variant="outline" size="sm" onClick={handleAddPrompt}>
            <Plus className="mr-2 size-4" />
            Add prompt
          </Button>
        </div>
      </div>

      <div className="mb-6 flex min-h-[50%] w-full gap-[var(--item-gap)]">
        {prompts.map((prompt, idx) => (
          <PlaygroundPrompt
            index={idx}
            key={prompt.id}
            onChange={handlePromptChange}
            onClickRemove={handlePromptRemove}
            onClickDuplicate={handlePromptDuplicate}
            workspaceName={workspaceName}
            {...prompt}
          />
        ))}
      </div>

      <PlaygroundOutputs
        prompts={prompts}
        workspaceName={workspaceName}
        onChange={handlePromptChange}
      />
    </div>
  );
};

export default PlaygroundPage;
