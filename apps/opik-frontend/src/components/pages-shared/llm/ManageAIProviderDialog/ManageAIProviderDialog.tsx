import React, { useCallback, useMemo, useState } from "react";
import { useForm, UseFormReturn } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { ChevronLeft } from "lucide-react";
import isFunction from "lodash/isFunction";
import { v4 as uuidv4 } from "uuid";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_TYPE,
  ProviderObject,
} from "@/types/providers";

import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useProviderKeysDeleteMutation from "@/api/provider-keys/useProviderKeysDeleteMutation";
import useProviderKeysUpdateMutation from "@/api/provider-keys/useProviderKeysUpdateMutation";
import useProviderKeysCreateMutation from "@/api/provider-keys/useProviderKeysCreateMutation";
import {
  createAIProviderFormSchema,
  AIProviderFormType,
} from "@/components/pages-shared/llm/ManageAIProviderDialog/schema";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import {
  convertCustomProviderModels,
  buildComposedProviderKey,
} from "@/lib/provider";
import { useProviderOptions } from "@/hooks/useProviderOptions";
import ProviderSelectionStep from "@/components/pages-shared/llm/SetupProviderDialog/ProviderSelectionStep";
import ProviderConfigurationStep from "@/components/pages-shared/llm/SetupProviderDialog/ProviderConfigurationStep";

/**
 * Converts header array from form state to API-compatible object format
 * @param headersArray - Array of header key-value pairs from form
 * @param isEditing - Whether editing an existing custom provider
 * @returns Headers object, empty object to clear, or undefined
 *
 * Three cases handled:
 * 1. Non-empty array → Convert to object, filtering empty keys
 * 2. Empty array when editing → Return {} to clear headers from backend
 * 3. Empty array when creating → Return undefined (don't send headers field)
 */
function convertHeadersForAPI(
  headersArray: Array<{ key: string; value: string }> | undefined,
  isEditing: boolean,
): Record<string, string> | undefined {
  if (headersArray === undefined) {
    return undefined;
  }

  // Case 1: Convert non-empty array to object, filtering empty keys
  if (headersArray.length > 0) {
    return headersArray.reduce<Record<string, string>>((acc, header) => {
      const trimmedKey = header.key.trim();
      if (trimmedKey) {
        acc[trimmedKey] = header.value;
      }
      return acc;
    }, {});
  }

  // Case 2: Empty array when editing = clear headers from backend
  if (isEditing) {
    return {};
  }

  // Case 3: Empty array when creating = don't send headers field
  return undefined;
}

type Step = "select" | "configure";

type ManageAIProviderDialogProps = {
  providerKey?: ProviderObject;
  open: boolean;
  setOpen: (open: boolean) => void;
  onAddProvider?: (provider: COMPOSED_PROVIDER_TYPE) => void;
  onDeleteProvider?: (provider: COMPOSED_PROVIDER_TYPE) => void;
  configuredProvidersList?: ProviderObject[];
};

// Label generator for "Add new" options
const addNewLabelGenerator = (providerType: PROVIDER_TYPE) => {
  if (providerType === PROVIDER_TYPE.BEDROCK) {
    return "Add Bedrock provider";
  }
  if (providerType === PROVIDER_TYPE.CUSTOM) {
    return "Add vLLM / Custom provider";
  }
  return "Add provider";
};

const ManageAIProviderDialog: React.FC<ManageAIProviderDialogProps> = ({
  providerKey,
  open,
  setOpen,
  onAddProvider,
  onDeleteProvider,
  configuredProvidersList,
}) => {
  // Ensure providerKey is included in the list for proper grid highlighting
  const effectiveConfiguredProvidersList = useMemo(() => {
    if (configuredProvidersList) return configuredProvidersList;
    if (providerKey) return [providerKey];
    return undefined;
  }, [configuredProvidersList, providerKey]);

  // Get provider options with configured status and "Add new" options
  const providerOptions = useProviderOptions({
    configuredProvidersList: effectiveConfiguredProvidersList,
    includeConfiguredStatus: true,
    includeAddNewOptions: true,
    addNewLabelGenerator,
  });

  const [step, setStep] = useState<Step>(providerKey ? "configure" : "select");
  const [confirmOpen, setConfirmOpen] = useState<boolean>(false);
  const [selectedProviderId, setSelectedProviderId] = useState<
    string | undefined
  >(providerKey?.id);
  const [selectedComposedProvider, setSelectedComposedProvider] = useState<
    COMPOSED_PROVIDER_TYPE | ""
  >(providerKey?.ui_composed_provider ?? "");
  const [selectedProviderType, setSelectedProviderType] = useState<
    PROVIDER_TYPE | ""
  >(providerKey?.provider ?? "");

  const { mutate: createMutate } = useProviderKeysCreateMutation();
  const { mutate: updateMutate } = useProviderKeysUpdateMutation();
  const { mutate: deleteMutate } = useProviderKeysDeleteMutation();

  const existingProviderNames = useMemo(() => {
    return effectiveConfiguredProvidersList
      ?.filter(
        (p) =>
          p.provider === PROVIDER_TYPE.CUSTOM ||
          p.provider === PROVIDER_TYPE.BEDROCK,
      )
      .map((p) => p.provider_name)
      .filter(Boolean) as string[];
  }, [effectiveConfiguredProvidersList]);

  const form: UseFormReturn<AIProviderFormType> = useForm<AIProviderFormType>({
    resolver: zodResolver(createAIProviderFormSchema(existingProviderNames)),
    defaultValues: {
      provider: providerKey?.provider,
      composedProviderType: providerKey?.ui_composed_provider,
      id: providerKey?.id,
      apiKey: "",
      location: providerKey?.configuration?.location ?? "",
      url: providerKey?.base_url ?? "",
      providerName: providerKey?.provider_name ?? "",
      models: convertCustomProviderModels(
        providerKey?.configuration?.models ?? "",
        providerKey?.provider_name,
      ),
      headers:
        providerKey?.headers && Object.keys(providerKey.headers).length > 0
          ? Object.entries(providerKey.headers).map(([key, value]) => ({
              key,
              value,
              id: uuidv4(),
            }))
          : [],
    } as AIProviderFormType,
  });

  const provider = form.watch("provider") as PROVIDER_TYPE | undefined;

  const calculatedProviderKey = useMemo(() => {
    return effectiveConfiguredProvidersList?.find(
      (p) => selectedProviderId === p.id,
    );
  }, [effectiveConfiguredProvidersList, selectedProviderId]);

  const isConfiguredProvider = Boolean(calculatedProviderKey);
  const isEdit = Boolean(providerKey || calculatedProviderKey);

  const title = isEdit
    ? "Edit provider configuration"
    : "Add provider configuration";

  const buttonText = provider
    ? providerKey || calculatedProviderKey
      ? "Update configuration"
      : "Add provider"
    : "Add provider";

  const customProviderName =
    selectedProviderType === PROVIDER_TYPE.CUSTOM ||
    selectedProviderType === PROVIDER_TYPE.BEDROCK
      ? calculatedProviderKey?.provider_name || providerKey?.provider_name
      : undefined;

  const resetSelectionState = useCallback(() => {
    setSelectedProviderId(undefined);
    setSelectedComposedProvider("");
    setSelectedProviderType("");
    form.reset({
      provider: undefined,
      composedProviderType: "",
      id: undefined,
      apiKey: "",
      location: "",
      url: "",
      providerName: "",
      models: "",
      headers: [],
    });
    setStep("select");
  }, [form]);

  const handleProviderSelect = useCallback(
    (
      composedProviderType: COMPOSED_PROVIDER_TYPE,
      providerType: PROVIDER_TYPE,
      configuredId?: string,
    ) => {
      setSelectedProviderId(configuredId);
      setSelectedComposedProvider(composedProviderType);
      setSelectedProviderType(providerType);

      const providerData = effectiveConfiguredProvidersList?.find(
        (c) => composedProviderType === c.ui_composed_provider,
      );

      form.setValue("id", providerData?.id);
      form.setValue("url", providerData?.base_url ?? "");
      form.setValue("providerName", providerData?.provider_name ?? "");
      form.setValue(
        "models",
        convertCustomProviderModels(
          providerData?.configuration?.models ?? "",
          providerData?.provider_name,
        ),
      );
      form.setValue("location", providerData?.configuration?.location ?? "");
      form.setValue(
        "headers",
        providerData?.headers && Object.keys(providerData.headers).length > 0
          ? Object.entries(providerData.headers).map(([key, value]) => ({
              key,
              value,
              id: uuidv4(),
            }))
          : [],
      );

      form.setValue("provider", providerType);
      form.setValue("composedProviderType", composedProviderType);
      form.setValue("apiKey", "");
      form.clearErrors();

      setStep("configure");
    },
    [form, effectiveConfiguredProvidersList],
  );

  const handleBack = useCallback(() => {
    resetSelectionState();
  }, [resetSelectionState]);

  const cloudConfigHandler = useCallback(() => {
    const apiKey = form.getValues("apiKey");
    const url = form.getValues("url");
    const location = form.getValues("location");
    const providerName = form.getValues("providerName");
    const headersArray = form.getValues("headers");
    const composedProviderType = buildComposedProviderKey(
      provider!,
      providerName,
    );
    const models = convertCustomProviderModels(
      form.getValues("models") ?? "",
      providerName ?? "",
      true,
    );
    const isVertex = provider === PROVIDER_TYPE.VERTEX_AI;
    const isCustom = provider === PROVIDER_TYPE.CUSTOM;
    const isBedrock = provider === PROVIDER_TYPE.BEDROCK;
    const isCustomLike = isCustom || isBedrock;

    const configuration =
      isVertex || isCustomLike
        ? {
            location: isVertex ? location : undefined,
            models: isCustomLike ? models : undefined,
          }
        : undefined;

    const isEditingCustomProvider =
      isCustomLike && !!(providerKey || calculatedProviderKey);
    const headers = convertHeadersForAPI(headersArray, isEditingCustomProvider);

    if (providerKey || calculatedProviderKey) {
      updateMutate({
        providerKey: {
          id: providerKey?.id ?? calculatedProviderKey?.id,
          apiKey,
          base_url: isCustomLike ? url : undefined,
          ...(configuration && { configuration }),
          ...(isCustomLike && headers !== undefined && { headers }),
        },
      });
    } else if (provider) {
      if (isFunction(onAddProvider)) {
        onAddProvider(composedProviderType);
      }

      createMutate({
        providerKey: {
          apiKey,
          provider,
          base_url: isCustomLike ? url : undefined,
          provider_name: isCustomLike ? providerName : undefined,
          ...(configuration && { configuration }),
          ...(isCustomLike && headers !== undefined && { headers }),
        },
      });
    }
    setOpen(false);
  }, [
    form,
    provider,
    providerKey,
    calculatedProviderKey,
    setOpen,
    updateMutate,
    onAddProvider,
    createMutate,
  ]);

  const deleteProviderKeyHandler = useCallback(() => {
    if (calculatedProviderKey) {
      deleteMutate(
        {
          providerId: calculatedProviderKey.id,
        },
        {
          onSuccess: () => {
            if (isFunction(onDeleteProvider)) {
              onDeleteProvider(calculatedProviderKey.ui_composed_provider);
            }

            resetSelectionState();
            setConfirmOpen(false);
          },
        },
      );
    }
  }, [
    calculatedProviderKey,
    onDeleteProvider,
    deleteMutate,
    resetSelectionState,
  ]);

  const handleCancel = useCallback(() => {
    setOpen(false);
    if (!providerKey) {
      resetSelectionState();
    }
  }, [setOpen, providerKey, resetSelectionState]);

  const handleOpenChange = useCallback(
    (isOpen: boolean) => {
      setOpen(isOpen);
      if (!isOpen && !providerKey) {
        resetSelectionState();
      }
    },
    [setOpen, providerKey, resetSelectionState],
  );

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-lg sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>
            <ExplainerDescription
              {...EXPLAINERS_MAP[EXPLAINER_ID.why_do_i_need_an_ai_provider]}
            />
          </DialogDescription>
        </DialogHeader>

        <DialogAutoScrollBody className="max-h-[60vh] pr-6">
          {step === "select" ? (
            <ProviderSelectionStep
              providerOptions={providerOptions}
              selectedComposedProvider={selectedComposedProvider}
              onSelectProvider={handleProviderSelect}
            />
          ) : (
            selectedProviderType && (
              <ProviderConfigurationStep
                selectedProviderType={selectedProviderType as PROVIDER_TYPE}
                form={form}
                onSubmit={cloudConfigHandler}
                isEdit={isEdit}
                customProviderName={customProviderName}
              />
            )
          )}
        </DialogAutoScrollBody>
        <DialogFooter>
          {step === "select" ? null : (
            <div className="flex w-full justify-between">
              <div className="flex items-center gap-2">
                {!providerKey && (
                  <Button
                    variant="ghost"
                    onClick={handleBack}
                    type="button"
                    className="p-0"
                  >
                    <ChevronLeft className="mr-1 size-4" />
                    Back
                  </Button>
                )}
              </div>
              <div className="flex gap-2">
                {isConfiguredProvider ? (
                  <Button
                    type="button"
                    variant="destructive"
                    onClick={() => setConfirmOpen(true)}
                  >
                    Delete configuration
                  </Button>
                ) : (
                  <Button
                    variant="outline"
                    onClick={handleCancel}
                    type="button"
                  >
                    Cancel
                  </Button>
                )}
                <Button
                  type="submit"
                  onClick={form.handleSubmit(cloudConfigHandler)}
                >
                  {buttonText}
                </Button>
              </div>
            </div>
          )}
        </DialogFooter>
      </DialogContent>
      <ConfirmDialog
        open={confirmOpen}
        setOpen={setConfirmOpen}
        onConfirm={deleteProviderKeyHandler}
        title="Delete configuration"
        description="This configuration is shared across the workspace. Deleting it will remove access for everyone. This action can't be undone. Are you sure you want to proceed?"
        confirmText="Delete configuration"
        confirmButtonVariant="destructive"
      />
    </Dialog>
  );
};

export default ManageAIProviderDialog;
