import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useForm, UseFormReturn } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";

import {
  Dialog,
  DialogAutoScrollBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Form } from "@/components/ui/form";
import { COMPOSED_PROVIDER_TYPE, PROVIDER_TYPE } from "@/types/providers";
import useProviderKeysCreateMutation from "@/api/provider-keys/useProviderKeysCreateMutation";
import ProviderGrid, {
  ProviderGridOption,
} from "@/components/pages-shared/llm/SetupProviderDialog/ProviderGrid";
import CloudAIProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/CloudAIProviderDetails";
import CustomProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/CustomProviderDetails";
import VertexAIProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/VertexAIProviderDetails";
import BedrockProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/BedrockProviderDetails";
import {
  AIProviderFormSchema,
  AIProviderFormType,
} from "@/components/pages-shared/llm/ManageAIProviderDialog/schema";
import {
  buildComposedProviderKey,
  convertCustomProviderModels,
} from "@/lib/provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { PROVIDERS, PROVIDERS_OPTIONS } from "@/constants/providers";

interface SetupProviderDialogProps {
  open: boolean;
  setOpen: (open: boolean) => void;
  onProviderAdded?: () => void;
}

const SetupProviderDialog: React.FC<SetupProviderDialogProps> = ({
  open,
  setOpen,
  onProviderAdded,
}) => {
  // Feature toggle hooks - all provider toggles live here in the parent
  const isOpenAIEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.OPENAI_PROVIDER_ENABLED,
  );
  const isAnthropicEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.ANTHROPIC_PROVIDER_ENABLED,
  );
  const isGeminiEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GEMINI_PROVIDER_ENABLED,
  );
  const isOpenRouterEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.OPENROUTER_PROVIDER_ENABLED,
  );
  const isVertexAIEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.VERTEXAI_PROVIDER_ENABLED,
  );
  const isBedrockEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.BEDROCK_PROVIDER_ENABLED,
  );
  const isCustomLLMEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.CUSTOMLLM_PROVIDER_ENABLED,
  );

  const providerEnabledMap = useMemo(
    () => ({
      [PROVIDER_TYPE.OPEN_AI]: isOpenAIEnabled,
      [PROVIDER_TYPE.ANTHROPIC]: isAnthropicEnabled,
      [PROVIDER_TYPE.GEMINI]: isGeminiEnabled,
      [PROVIDER_TYPE.OPEN_ROUTER]: isOpenRouterEnabled,
      [PROVIDER_TYPE.VERTEX_AI]: isVertexAIEnabled,
      [PROVIDER_TYPE.BEDROCK]: isBedrockEnabled,
      [PROVIDER_TYPE.CUSTOM]: isCustomLLMEnabled,
    }),
    [
      isOpenAIEnabled,
      isAnthropicEnabled,
      isGeminiEnabled,
      isOpenRouterEnabled,
      isVertexAIEnabled,
      isBedrockEnabled,
      isCustomLLMEnabled,
    ],
  );

  // Build provider options - this logic was moved from ProviderGrid
  const providerOptions = useMemo(() => {
    const options: ProviderGridOption[] = [];

    // Filter standard providers based on feature flags
    const standardProviders = PROVIDERS_OPTIONS.filter((option) => {
      if (
        option.value === PROVIDER_TYPE.CUSTOM ||
        option.value === PROVIDER_TYPE.OPIK_FREE ||
        option.value === PROVIDER_TYPE.BEDROCK
      ) {
        return false;
      }
      return providerEnabledMap[option.value];
    });

    standardProviders.forEach((option) => {
      options.push({
        value: buildComposedProviderKey(option.value),
        label: option.label,
        providerType: option.value,
      });
    });

    // Add Bedrock provider option for creating new instances
    if (isBedrockEnabled) {
      options.push({
        value: buildComposedProviderKey(
          PROVIDER_TYPE.BEDROCK,
          "__add_bedrock_provider__",
        ),
        label: PROVIDERS[PROVIDER_TYPE.BEDROCK].label,
        providerType: PROVIDER_TYPE.BEDROCK,
      });
    }

    // Add custom provider option for creating new instances
    if (isCustomLLMEnabled) {
      options.push({
        value: buildComposedProviderKey(
          PROVIDER_TYPE.CUSTOM,
          "__add_custom_provider__",
        ),
        label: PROVIDERS[PROVIDER_TYPE.CUSTOM].label,
        providerType: PROVIDER_TYPE.CUSTOM,
      });
    }

    return options;
  }, [providerEnabledMap, isCustomLLMEnabled, isBedrockEnabled]);

  const [selectedComposedProvider, setSelectedComposedProvider] = useState<
    COMPOSED_PROVIDER_TYPE | ""
  >("");
  const [selectedProviderType, setSelectedProviderType] = useState<
    PROVIDER_TYPE | ""
  >("");

  const { mutate: createProviderKey } = useProviderKeysCreateMutation();

  const form: UseFormReturn<AIProviderFormType> = useForm<AIProviderFormType>({
    resolver: zodResolver(AIProviderFormSchema),
    defaultValues: {
      provider: PROVIDER_TYPE.OPEN_AI,
      composedProviderType: "",
      apiKey: "",
      location: "",
      url: "",
      models: "",
      providerName: "",
      headers: [],
    } as AIProviderFormType,
  });

  // Auto-select first available provider when options change
  useEffect(() => {
    if (!selectedComposedProvider && providerOptions.length > 0) {
      const firstOption = providerOptions[0];
      setSelectedComposedProvider(firstOption.value);
      setSelectedProviderType(firstOption.providerType);
      form.setValue("provider", firstOption.providerType);
      form.setValue("composedProviderType", firstOption.value);
    }
  }, [providerOptions, selectedComposedProvider, form]);

  const handleProviderSelect = useCallback(
    (
      composedProviderType: COMPOSED_PROVIDER_TYPE,
      providerType: PROVIDER_TYPE,
    ) => {
      setSelectedComposedProvider(composedProviderType);
      setSelectedProviderType(providerType);
      form.setValue("provider", providerType);
      form.setValue("composedProviderType", composedProviderType);
      form.setValue("apiKey", "");
      form.clearErrors();
    },
    [form],
  );

  const handleSubmit = useCallback(
    (data: AIProviderFormType) => {
      const isCustom = data.provider === PROVIDER_TYPE.CUSTOM;
      const isBedrock = data.provider === PROVIDER_TYPE.BEDROCK;
      const isVertex = data.provider === PROVIDER_TYPE.VERTEX_AI;

      const providerKeyData: Partial<{
        provider: PROVIDER_TYPE;
        provider_name: string;
        apiKey: string;
        base_url: string;
        configuration: Record<string, string>;
        headers: Record<string, string>;
      }> = {
        provider: data.provider as PROVIDER_TYPE,
        ...(data.apiKey && { apiKey: data.apiKey }),
      };

      if (
        (isCustom || isBedrock) &&
        "url" in data &&
        "models" in data &&
        "providerName" in data
      ) {
        providerKeyData.base_url = data.url;
        providerKeyData.provider_name = data.providerName;
        providerKeyData.configuration = {
          models: convertCustomProviderModels(
            data.models ?? "",
            data.providerName ?? "",
            true,
          ),
        };

        // Add headers if present
        if ("headers" in data && data.headers && Array.isArray(data.headers)) {
          const headersObj = data.headers.reduce<Record<string, string>>(
            (acc, header) => {
              const trimmedKey = header.key?.trim();
              if (trimmedKey) {
                acc[trimmedKey] = header.value;
              }
              return acc;
            },
            {},
          );
          if (Object.keys(headersObj).length > 0) {
            providerKeyData.headers = headersObj;
          }
        }
      }

      if (isVertex && "location" in data) {
        providerKeyData.configuration = {
          location: data.location,
        };
      }

      createProviderKey(
        {
          providerKey: providerKeyData,
        },
        {
          onSuccess: () => {
            setOpen(false);
            form.reset();
            setSelectedComposedProvider("");
            setSelectedProviderType("");

            onProviderAdded?.();
          },
        },
      );
    },
    [createProviderKey, setOpen, form, onProviderAdded],
  );

  const handleCancel = useCallback(() => {
    setOpen(false);
    form.reset();
    setSelectedComposedProvider("");
    setSelectedProviderType("");
  }, [setOpen, form]);

  const renderContent = () => {
    if (providerOptions.length === 0) {
      return (
        <div className="comet-body-s text-muted-foreground">
          No providers available for this environment
        </div>
      );
    }

    return (
      <DialogAutoScrollBody className="flex flex-col pr-6">
        <p className="comet-body-s mb-4 text-muted-foreground">
          To use the Playground, select an AI provider and enter your API key
        </p>

        <Form {...form}>
          <form
            onSubmit={form.handleSubmit(handleSubmit)}
            className="flex flex-col gap-4"
          >
            <ProviderGrid
              options={providerOptions}
              selectedProvider={selectedComposedProvider}
              onSelectProvider={handleProviderSelect}
            />

            {selectedProviderType && (
              <>
                {selectedProviderType === PROVIDER_TYPE.CUSTOM ? (
                  <CustomProviderDetails form={form} />
                ) : selectedProviderType === PROVIDER_TYPE.BEDROCK ? (
                  <BedrockProviderDetails form={form} />
                ) : selectedProviderType === PROVIDER_TYPE.VERTEX_AI ? (
                  <VertexAIProviderDetails form={form} />
                ) : (
                  <CloudAIProviderDetails
                    provider={selectedProviderType}
                    form={form}
                  />
                )}
              </>
            )}
          </form>
        </Form>
      </DialogAutoScrollBody>
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>Add an AI provider</DialogTitle>
        </DialogHeader>

        {renderContent()}

        <DialogFooter>
          <Button variant="outline" onClick={handleCancel} type="button">
            Cancel
          </Button>
          <Button
            type="submit"
            disabled={!selectedProviderType}
            onClick={form.handleSubmit(handleSubmit)}
          >
            Done
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default SetupProviderDialog;
