import React, { useCallback, useState } from "react";
import { useForm, UseFormReturn } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";

import {
  Dialog,
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

  const form: UseFormReturn<AIProviderFormType> = useForm<
    z.infer<typeof AIProviderFormSchema>
  >({
    resolver: zodResolver(AIProviderFormSchema),
    defaultValues: {
      provider: PROVIDER_TYPE.OPEN_AI,
      apiKey: "",
      // @ts-expect-error - union type compatibility
      url: "",
      models: "",
      location: "",
    },
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
      const providerKeyData: Partial<{
        provider: PROVIDER_TYPE;
        apiKey: string;
        base_url: string;
        configuration: Record<string, string>;
      }> = {
        provider: data.provider as PROVIDER_TYPE,
        ...(data.apiKey && { apiKey: data.apiKey }),
      };

      // Handle custom provider
      if (
        data.provider === PROVIDER_TYPE.CUSTOM &&
        "url" in data &&
        "models" in data
      ) {
        providerKeyData.base_url = data.url;
        providerKeyData.configuration = {
          models: data.models,
        };
      }

      // Handle Vertex AI
      if (data.provider === PROVIDER_TYPE.VERTEX_AI && "location" in data) {
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

            // Notify parent that provider was added
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
        <div className="flex flex-col gap-4 pb-4">
          <p className="comet-body-s text-muted-foreground">
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

              <DialogFooter>
                <Button variant="outline" onClick={handleCancel} type="button">
                  Cancel
                </Button>
                <Button type="submit" disabled={!selectedProvider}>
                  Done
                </Button>
              </DialogFooter>
            </form>
          </Form>
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default SetupProviderDialog;
