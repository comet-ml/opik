import React, { useCallback, useEffect, useState } from "react";
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
import ProviderGrid from "@/components/pages-shared/llm/SetupProviderDialog/ProviderGrid";
import CloudAIProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/CloudAIProviderDetails";
import CustomProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/CustomProviderDetails";
import VertexAIProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/VertexAIProviderDetails";
import BedrockProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/BedrockProviderDetails";
import {
  AIProviderFormSchema,
  AIProviderFormType,
} from "@/components/pages-shared/llm/ManageAIProviderDialog/schema";
import { convertCustomProviderModels } from "@/lib/provider";
import { useProviderOptions } from "@/hooks/useProviderOptions";

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
  // Use shared hook for provider options with feature toggles
  const providerOptions = useProviderOptions();

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
