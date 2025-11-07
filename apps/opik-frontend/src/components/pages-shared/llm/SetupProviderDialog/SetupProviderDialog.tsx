import React, { useCallback, useState } from "react";
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
import { PROVIDER_TYPE } from "@/types/providers";
import useProviderKeysCreateMutation from "@/api/provider-keys/useProviderKeysCreateMutation";
import ProviderGrid from "@/components/pages-shared/llm/SetupProviderDialog/ProviderGrid";
import CloudAIProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/CloudAIProviderDetails";
import CustomProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/CustomProviderDetails";
import VertexAIProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/VertexAIProviderDetails";
import {
  AIProviderFormSchema,
  AIProviderFormType,
} from "@/components/pages-shared/llm/ManageAIProviderDialog/schema";
import { convertCustomProviderModels } from "@/lib/provider";

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
  const [selectedProvider, setSelectedProvider] = useState<PROVIDER_TYPE | "">(
    PROVIDER_TYPE.OPEN_AI,
  );
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
    } as AIProviderFormType,
  });

  const handleProviderSelect = useCallback(
    (provider: PROVIDER_TYPE) => {
      setSelectedProvider(provider);
      form.setValue("provider", provider);
      form.setValue("apiKey", "");
      form.clearErrors();
    },
    [form],
  );

  const handleSubmit = useCallback(
    (data: AIProviderFormType) => {
      const isCustom = data.provider === PROVIDER_TYPE.CUSTOM;
      const isVertex = data.provider === PROVIDER_TYPE.VERTEX_AI;

      const providerKeyData: Partial<{
        provider: PROVIDER_TYPE;
        provider_name: string;
        apiKey: string;
        base_url: string;
        configuration: Record<string, string>;
      }> = {
        provider: data.provider as PROVIDER_TYPE,
        ...(data.apiKey && { apiKey: data.apiKey }),
      };

      if (
        isCustom &&
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
            setSelectedProvider("");

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
    setSelectedProvider("");
  }, [setOpen, form]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Add an AI provider</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody className="flex flex-col">
          <p className="comet-body-s mb-4 text-muted-foreground">
            To use the Playground, select an AI provider and enter your API key
          </p>

          <Form {...form}>
            <form
              onSubmit={form.handleSubmit(handleSubmit)}
              className="flex flex-col gap-4"
            >
              <ProviderGrid
                selectedProvider={selectedProvider}
                onSelectProvider={handleProviderSelect}
              />

              {selectedProvider && (
                <>
                  {selectedProvider === PROVIDER_TYPE.CUSTOM ? (
                    <CustomProviderDetails form={form} />
                  ) : selectedProvider === PROVIDER_TYPE.VERTEX_AI ? (
                    <VertexAIProviderDetails form={form} />
                  ) : (
                    <CloudAIProviderDetails
                      provider={selectedProvider}
                      form={form}
                    />
                  )}
                </>
              )}
            </form>
          </Form>
        </DialogAutoScrollBody>
        <DialogFooter>
          <Button variant="outline" onClick={handleCancel} type="button">
            Cancel
          </Button>
          <Button
            type="submit"
            disabled={!selectedProvider}
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
