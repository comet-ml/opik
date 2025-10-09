import React, { useCallback, useEffect, useRef } from "react";
import PromptModelSelect from "@/components/pages-shared/llm/PromptModelSelect/PromptModelSelect";
import PromptModelConfigs from "@/components/pages-shared/llm/PromptModelSettings/PromptModelConfigs";
import { LLMChatType } from "@/types/llm";
import {
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { getDefaultConfigByProvider } from "@/lib/playground";
import { useUpdateChat } from "@/store/ChatStore";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";

type ChatModelSettingsProps = {
  workspaceName: string;
  chat: LLMChatType;
  providerKeys: PROVIDER_TYPE[];
  isPendingProviderKeys: boolean;
};

const ChatModelSettings: React.FC<ChatModelSettingsProps> = ({
  workspaceName,
  chat,
  providerKeys,
  isPendingProviderKeys,
}) => {
  const { model, provider, configs } = chat;
  const checkedIfModelIsValidRef = useRef(false);
  const {
    calculateModelProvider: providerResolver,
    calculateDefaultModel: modelResolver,
  } = useLLMProviderModelsData();

  const updateChat = useUpdateChat();

  useEffect(() => {
    // on init, to check if a chat has a model from valid providers: (f.e., remove a provider after setting a model)
    if (!checkedIfModelIsValidRef.current && !isPendingProviderKeys) {
      checkedIfModelIsValidRef.current = true;

      const newModel = modelResolver(model, providerKeys);

      if (newModel !== model) {
        const newProvider = providerResolver(newModel);
        updateChat({
          model: newModel,
          provider: newProvider,
          configs: getDefaultConfigByProvider(newProvider, newModel),
        });
      }
    }
  }, [
    providerKeys,
    isPendingProviderKeys,
    providerResolver,
    modelResolver,
    model,
    updateChat,
  ]);

  const handleUpdateModel = useCallback(
    (newModel: PROVIDER_MODEL_TYPE, newProvider: PROVIDER_TYPE) => {
      updateChat({
        model: newModel,
        provider: newProvider,
        ...(newProvider !== provider && {
          configs: getDefaultConfigByProvider(newProvider, newModel),
        }),
      });
    },
    [provider, updateChat],
  );

  const handleAddProvider = useCallback(
    (provider: PROVIDER_TYPE) => {
      const newModel = modelResolver(model, providerKeys, provider);

      if (newModel !== model) {
        const newProvider = providerResolver(newModel);
        updateChat({
          model: newModel,
          provider: newProvider,
          configs: getDefaultConfigByProvider(newProvider, newModel),
        });
      }
    },
    [modelResolver, model, providerKeys, providerResolver, updateChat],
  );

  const handleUpdateConfig = useCallback(
    (newConfigs: Partial<LLMPromptConfigsType>) => {
      updateChat({
        configs: {
          ...configs,
          ...newConfigs,
        } as LLMPromptConfigsType,
      });
    },
    [configs, updateChat],
  );

  return (
    <div className="flex w-full items-center gap-1">
      <div className="h-8 flex-auto">
        <PromptModelSelect
          value={model}
          onChange={handleUpdateModel}
          provider={provider}
          workspaceName={workspaceName}
          onAddProvider={handleAddProvider}
          hasError={!model}
        />
      </div>
      <PromptModelConfigs
        provider={provider}
        model={model}
        configs={configs}
        onChange={handleUpdateConfig}
      />
    </div>
  );
};

export default ChatModelSettings;
