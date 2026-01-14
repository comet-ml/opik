import React, { useCallback, useState } from "react";
import { useForm, UseFormReturn } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";

import {
  Dialog,
  DialogAutoScrollBody,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { COMPOSED_PROVIDER_TYPE, PROVIDER_TYPE } from "@/types/providers";
import useProviderKeysCreateMutation from "@/api/provider-keys/useProviderKeysCreateMutation";
import ProviderSelectionStep from "./ProviderSelectionStep";
import ProviderConfigurationStep from "./ProviderConfigurationStep";
import {
  AIProviderFormSchema,
  AIProviderFormType,
} from "@/components/pages-shared/llm/ManageAIProviderDialog/schema";
import { convertCustomProviderModels } from "@/lib/provider";
import { useProviderOptions } from "@/hooks/useProviderOptions";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import { ChevronLeft } from "lucide-react";

interface SetupProviderDialogProps {
  open: boolean;
  setOpen: (open: boolean) => void;
  onProviderAdded?: () => void;
}

type Step = "select" | "configure";

const SetupProviderDialog: React.FC<SetupProviderDialogProps> = ({
  open,
  setOpen,
  onProviderAdded,
}) => {
  const providerOptions = useProviderOptions({
    includeAddNewOptions: true,
  });

  const [step, setStep] = useState<Step>("select");
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
      setStep("configure");
    },
    [form],
  );

  const handleBack = useCallback(() => {
    setSelectedComposedProvider("");
    setSelectedProviderType("");
    form.reset();
    setStep("select");
  }, [form]);

  const resetDialog = useCallback(() => {
    form.reset();
    setSelectedComposedProvider("");
    setSelectedProviderType("");
    setStep("select");
  }, [form]);

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
            resetDialog();
            onProviderAdded?.();
          },
        },
      );
    },
    [createProviderKey, setOpen, onProviderAdded, resetDialog],
  );

  const handleCancel = useCallback(() => {
    setOpen(false);
    resetDialog();
  }, [setOpen, resetDialog]);

  const handleOpenChange = useCallback(
    (isOpen: boolean) => {
      setOpen(isOpen);
      if (!isOpen) {
        resetDialog();
      }
    },
    [setOpen, resetDialog],
  );

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-lg sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>Add provider configuration</DialogTitle>
          <DialogDescription>
            <ExplainerDescription
              {...EXPLAINERS_MAP[EXPLAINER_ID.why_do_i_need_an_ai_provider]}
            />
          </DialogDescription>
        </DialogHeader>

        <DialogAutoScrollBody className="flex flex-col pr-6">
          {step === "select" ? (
            <ProviderSelectionStep
              providerOptions={providerOptions}
              selectedComposedProvider={selectedComposedProvider}
              onSelectProvider={handleProviderSelect}
            />
          ) : (
            selectedProviderType && (
              <ProviderConfigurationStep
                selectedProviderType={selectedProviderType}
                form={form}
                onSubmit={handleSubmit}
              />
            )
          )}
        </DialogAutoScrollBody>

        <DialogFooter>
          {step === "select" ? null : (
            <div className="flex w-full justify-between">
              <Button
                variant="ghost"
                onClick={handleBack}
                type="button"
                className="p-0"
              >
                <ChevronLeft className="mr-1 size-4" />
                Back
              </Button>
              <div className="flex gap-2">
                <Button variant="outline" onClick={handleCancel} type="button">
                  Cancel
                </Button>
                <Button type="submit" onClick={form.handleSubmit(handleSubmit)}>
                  Add provider
                </Button>
              </div>
            </div>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default SetupProviderDialog;
