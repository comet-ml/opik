import React, { useCallback, useMemo, useState } from "react";
import { useForm, UseFormReturn } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { MessageCircleWarning } from "lucide-react";
import { z } from "zod";
import get from "lodash/get";
import isFunction from "lodash/isFunction";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormMessage,
} from "@/components/ui/form";
import { Label } from "@/components/ui/label";
import { PROVIDER_TYPE, ProviderKey } from "@/types/providers";

import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import ExplainerCallout from "@/components/shared/ExplainerCallout/ExplainerCallout";
import useProviderKeysDeleteMutation from "@/api/provider-keys/useProviderKeysDeleteMutation";
import ProviderSelect from "@/components/pages-shared/llm/ProviderSelect/ProviderSelect";
import useProviderKeysUpdateMutation from "@/api/provider-keys/useProviderKeysUpdateMutation";
import useProviderKeysCreateMutation from "@/api/provider-keys/useProviderKeysCreateMutation";
import {
  AIProviderFormSchema,
  AIProviderFormType,
} from "@/components/pages-shared/llm/ManageAIProviderDialog/schema";
import CloudAIProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/CloudAIProviderDetails";
import VertexAIProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/VertexAIProviderDetails";
import CustomProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/CustomProviderDetails";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import { convertCustomProviderModels } from "@/lib/provider";

type ManageAIProviderDialogProps = {
  providerKey?: ProviderKey;
  open: boolean;
  setOpen: (open: boolean) => void;
  onAddProvider?: (provider: PROVIDER_TYPE) => void;
  onDeleteProvider?: (provider: PROVIDER_TYPE) => void;
  configuredProvidersList?: ProviderKey[];
  defaultProvider?: PROVIDER_TYPE;
  forceCreateMode?: boolean; // When true, always show create form even if provider exists
};

const ManageAIProviderDialog: React.FC<ManageAIProviderDialogProps> = ({
  providerKey,
  open,
  setOpen,
  onAddProvider,
  onDeleteProvider,
  configuredProvidersList,
  defaultProvider,
  forceCreateMode = false,
}) => {
  const [confirmOpen, setConfirmOpen] = useState<boolean>(false);
  const [isAddingCustomProvider, setIsAddingCustomProvider] = useState(false);
  const { mutate: createMutate } = useProviderKeysCreateMutation();
  const { mutate: updateMutate } = useProviderKeysUpdateMutation();
  const { mutate: deleteMutate } = useProviderKeysDeleteMutation();

  const form: UseFormReturn<AIProviderFormType> = useForm<
    z.infer<typeof AIProviderFormSchema>
  >({
    resolver: zodResolver(AIProviderFormSchema),
    defaultValues: {
      provider: providerKey?.provider || defaultProvider || "",
      apiKey: "",
      location: providerKey?.configuration?.location ?? "",
      url: providerKey?.base_url ?? "",
      providerName: providerKey?.keyName ?? "",
      models: convertCustomProviderModels(
        providerKey?.configuration?.models ?? "",
      ),
    } as AIProviderFormType,
  });

  const provider = form.watch("provider") as PROVIDER_TYPE | string | "";

  const configuredProviderKeys = useMemo(
    () => (configuredProvidersList || []).map((p) => p.provider),
    [configuredProvidersList],
  );

  const calculatedProviderKey = useMemo(() => {
    // Don't auto-load existing provider in forceCreateMode or when adding custom provider
    if (forceCreateMode || isAddingCustomProvider) {
      return undefined;
    }

    // If provider is a custom provider ID (not a PROVIDER_TYPE enum)
    if (
      provider &&
      !Object.values(PROVIDER_TYPE).includes(provider as PROVIDER_TYPE)
    ) {
      return configuredProvidersList?.find((p) => p.id === provider);
    }

    // Standard provider lookup
    return configuredProvidersList?.find((p) => provider === p.provider);
  }, [configuredProvidersList, provider, forceCreateMode, isAddingCustomProvider]);

  const isConfiguredProvider = Boolean(calculatedProviderKey);
  const isEdit = Boolean(providerKey || calculatedProviderKey);
  const title = isEdit
    ? "Edit provider configuration"
    : "Add provider configuration";

  const buttonText = provider
    ? providerKey || calculatedProviderKey
      ? "Update configuration"
      : "Add configuration"
    : "Done";

  const cloudConfigHandler = useCallback((data: AIProviderFormType) => {
    // Use the validated data passed from form.handleSubmit instead of form.getValues()
    const apiKey = data.apiKey;
    const url = "url" in data ? data.url : "";
    const location = "location" in data ? data.location : "";
    const providerName = "providerName" in data ? data.providerName : "";
    const modelsRaw = "models" in data ? (data.models ?? "") : "";
    const models = convertCustomProviderModels(modelsRaw, true);
    
    // Determine the actual provider type
    // If isAddingCustomProvider is true, we know it's a custom provider
    const actualProvider = isAddingCustomProvider
      ? PROVIDER_TYPE.CUSTOM
      : (calculatedProviderKey?.provider || 
        (Object.values(PROVIDER_TYPE).includes(provider as PROVIDER_TYPE)
          ? (provider as PROVIDER_TYPE)
          : PROVIDER_TYPE.CUSTOM));
    
    const isVertex = actualProvider === PROVIDER_TYPE.VERTEX_AI;
    const isCustom = actualProvider === PROVIDER_TYPE.CUSTOM;

    const configuration =
      isVertex || isCustom
        ? {
            location: isVertex ? location : undefined,
            models: isCustom ? models : undefined,
          }
        : undefined;

    if (providerKey || calculatedProviderKey) {
      updateMutate({
        providerKey: {
          id: providerKey?.id ?? calculatedProviderKey?.id,
          apiKey,
          base_url: isCustom ? url : undefined,
          keyName: isCustom ? providerName : undefined,
          ...(configuration && { configuration }),
        },
      });
    } else if (provider || isAddingCustomProvider) {
      // Use provider from form or isAddingCustomProvider flag for new providers
      if (isFunction(onAddProvider)) {
        onAddProvider(actualProvider);
      }

      createMutate({
        providerKey: {
          apiKey,
          provider: actualProvider,
          base_url: isCustom ? url : undefined,
          keyName: isCustom ? providerName : undefined,
          ...(configuration && { configuration }),
        },
      });
    }
    setIsAddingCustomProvider(false);
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
    isAddingCustomProvider,
  ]);

  const handleSubmitClick = useCallback((e: React.MouseEvent<HTMLButtonElement>) => {
    e.preventDefault();
    
    // Ensure provider field is set before validation when adding custom provider
    if (isAddingCustomProvider && form.getValues("provider") !== PROVIDER_TYPE.CUSTOM) {
      form.setValue("provider", PROVIDER_TYPE.CUSTOM);
    }
    
    // Trigger form submission with validation and pass validated data to handler
    form.handleSubmit(cloudConfigHandler)();
  }, [form, isAddingCustomProvider, cloudConfigHandler]);

  const deleteProviderKeyHandler = useCallback(() => {
    if (calculatedProviderKey) {
      deleteMutate({
        providerId: calculatedProviderKey.id,
      });
    }

    if (isFunction(onDeleteProvider)) {
      onDeleteProvider(provider as PROVIDER_TYPE);
    }
  }, [provider, calculatedProviderKey, onDeleteProvider, deleteMutate]);

  const handleAddCustomProvider = useCallback(() => {
    setIsAddingCustomProvider(true);
    // Set provider value without resetting other fields
    // This allows the form to properly track changes to providerName, url, and models
    form.setValue("provider", PROVIDER_TYPE.CUSTOM, { shouldDirty: true, shouldTouch: true });
  }, [form]);
  
  // Reset isAddingCustomProvider when dialog closes
  React.useEffect(() => {
    if (!open) {
      setIsAddingCustomProvider(false);
    }
  }, [open]);

  const getProviderDetails = () => {
    if (provider === PROVIDER_TYPE.VERTEX_AI) {
      return <VertexAIProviderDetails form={form} />;
    }

    // When adding a custom provider, the form.reset() might not have updated the watched
    // provider value yet, so we need to check the isAddingCustomProvider flag
    // Check if provider is CUSTOM_LLM type or a custom provider ID (UUID)
    const isCustomProvider =
      isAddingCustomProvider ||
      provider === PROVIDER_TYPE.CUSTOM ||
      (provider &&
        !Object.values(PROVIDER_TYPE).includes(provider as PROVIDER_TYPE));

    if (isCustomProvider) {
      return <CustomProviderDetails form={form} />;
    }

    return <CloudAIProviderDetails provider={provider as PROVIDER_TYPE} form={form} />;
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <ExplainerDescription
            className="mb-4"
            {...EXPLAINERS_MAP[EXPLAINER_ID.why_do_i_need_an_ai_provider]}
          />
          <Form {...form}>
            <form
              className="flex flex-col gap-4 pb-4"
              onSubmit={form.handleSubmit(cloudConfigHandler)}
            >
              <FormField
                control={form.control}
                name="provider"
                render={({ field, formState }) => {
                  const validationErrors = get(formState.errors, ["provider"]);

                  return (
                    <FormItem>
                      <Label>Provider</Label>
                      <FormControl>
                        <ProviderSelect
                          disabled={Boolean(providerKey) || forceCreateMode || isAddingCustomProvider}
                          value={(field.value as string) || ""}
                          onChange={(v) => {
                            // Check if it's a custom provider ID
                            const customProvider = configuredProvidersList?.find(
                              (c) => c.id === v
                            );
                            
                            // For custom providers, store the ID in the provider field
                            // The calculatedProviderKey will use this to find the actual provider
                            if (customProvider) {
                              // Store the custom provider ID so calculatedProviderKey can find it
                              field.onChange(v);
                              
                              // Populate the form fields with the custom provider's data
                              form.setValue("url", customProvider.base_url ?? "");
                              form.setValue("providerName", customProvider.keyName ?? "");
                              form.setValue(
                                "models",
                                convertCustomProviderModels(
                                  customProvider.configuration?.models ?? "",
                                ),
                              );
                              form.setValue("location", "");
                            } else {
                              // For standard providers, store the PROVIDER_TYPE
                              const p = v as PROVIDER_TYPE;
                              field.onChange(p);
                              
                              const providerData = configuredProvidersList?.find(
                                (c) => p === c.provider
                              );

                              form.setValue("url", providerData?.base_url ?? "");
                              form.setValue("providerName", providerData?.keyName ?? "");
                              form.setValue(
                                "models",
                                convertCustomProviderModels(
                                  providerData?.configuration?.models ?? "",
                                ),
                              );
                              form.setValue(
                                "location",
                                providerData?.configuration?.location ?? "",
                              );
                            }
                          }}
                          configuredProviderKeys={configuredProviderKeys}
                          configuredProvidersList={configuredProvidersList}
                          hasError={Boolean(validationErrors?.message)}
                          onAddCustomProvider={handleAddCustomProvider}
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  );
                }}
              />
              {(isConfiguredProvider || providerKey) && (
                <ExplainerCallout
                  Icon={MessageCircleWarning}
                  isDismissable={false}
                  {...EXPLAINERS_MAP[
                    EXPLAINER_ID.what_happens_if_i_edit_an_ai_provider
                  ]}
                />
              )}
              {getProviderDetails()}
            </form>
          </Form>
        </DialogAutoScrollBody>
        <DialogFooter>
          {isConfiguredProvider && (
            <>
              <Button
                type="button"
                variant="destructive"
                onClick={() => setConfirmOpen(true)}
              >
                Delete configuration
              </Button>
              <div className="flex flex-auto"></div>
            </>
          )}
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button type="submit" onClick={handleSubmitClick}>
            {buttonText}
          </Button>
        </DialogFooter>
      </DialogContent>
      <ConfirmDialog
        open={confirmOpen}
        setOpen={setConfirmOpen}
        onConfirm={deleteProviderKeyHandler}
        title="Delete configuration"
        description="This configuration is shared across the workspace. Deleting it will remove access for everyone. This action can’t be undone. Are you sure you want to proceed?"
        confirmText="Delete configuration"
        confirmButtonVariant="destructive"
      />
    </Dialog>
  );
};

export default ManageAIProviderDialog;
