import React, { useCallback, useMemo, useState } from "react";
import { useForm, UseFormReturn } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { MessageCircleWarning } from "lucide-react";
import isFunction from "lodash/isFunction";
import { v4 as uuidv4 } from "uuid";

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
import { Form } from "@/components/ui/form";
import {
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_TYPE,
  ProviderObject,
} from "@/types/providers";

import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import ExplainerCallout from "@/components/shared/ExplainerCallout/ExplainerCallout";
import useProviderKeysDeleteMutation from "@/api/provider-keys/useProviderKeysDeleteMutation";
import ProviderGrid from "@/components/pages-shared/llm/SetupProviderDialog/ProviderGrid";
import useProviderKeysUpdateMutation from "@/api/provider-keys/useProviderKeysUpdateMutation";
import useProviderKeysCreateMutation from "@/api/provider-keys/useProviderKeysCreateMutation";
import {
  createAIProviderFormSchema,
  AIProviderFormType,
} from "@/components/pages-shared/llm/ManageAIProviderDialog/schema";
import CloudAIProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/CloudAIProviderDetails";
import VertexAIProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/VertexAIProviderDetails";
import CustomProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/CustomProviderDetails";
import BedrockProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/BedrockProviderDetails";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import {
  convertCustomProviderModels,
  buildComposedProviderKey,
} from "@/lib/provider";
import { useProviderOptions } from "@/hooks/useProviderOptions";

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

type ManageAIProviderDialogProps = {
  providerKey?: ProviderObject;
  open: boolean;
  setOpen: (open: boolean) => void;
  onAddProvider?: (provider: COMPOSED_PROVIDER_TYPE) => void;
  onDeleteProvider?: (provider: COMPOSED_PROVIDER_TYPE) => void;
  configuredProvidersList?: ProviderObject[];
};

const ManageAIProviderDialog: React.FC<ManageAIProviderDialogProps> = ({
  providerKey,
  open,
  setOpen,
  onAddProvider,
  onDeleteProvider,
  configuredProvidersList,
}) => {
  // Use shared hook for provider options with feature toggles
  const providerOptions = useProviderOptions({
    configuredProvidersList,
    includeConfiguredStatus: true,
  });

  const [confirmOpen, setConfirmOpen] = useState<boolean>(false);
  const [selectedProviderId, setSelectedProviderId] = useState<
    string | undefined
  >(providerKey?.id);
  const [selectedComposedProvider, setSelectedComposedProvider] = useState<
    COMPOSED_PROVIDER_TYPE | ""
  >(providerKey?.ui_composed_provider ?? "");
  const { mutate: createMutate } = useProviderKeysCreateMutation();
  const { mutate: updateMutate } = useProviderKeysUpdateMutation();
  const { mutate: deleteMutate } = useProviderKeysDeleteMutation();

  const existingProviderNames = useMemo(() => {
    return configuredProvidersList
      ?.filter(
        (p) =>
          p.provider === PROVIDER_TYPE.CUSTOM ||
          p.provider === PROVIDER_TYPE.BEDROCK,
      )
      .map((p) => p.provider_name)
      .filter(Boolean) as string[];
  }, [configuredProvidersList]);

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
    return configuredProvidersList?.find((p) => selectedProviderId === p.id);
  }, [configuredProvidersList, selectedProviderId]);

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

  const handleProviderSelect = useCallback(
    (
      composedProviderType: COMPOSED_PROVIDER_TYPE,
      providerType: PROVIDER_TYPE,
      configuredId?: string,
    ) => {
      setSelectedProviderId(configuredId);
      setSelectedComposedProvider(composedProviderType);

      const providerData = configuredProvidersList?.find(
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
    },
    [form, configuredProvidersList],
  );

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

            form.reset({
              provider: undefined,
              composedProviderType: "",
              id: undefined,
              apiKey: "",
              location: "",
              url: "",
              providerName: "",
              models: "",
            });
            setSelectedProviderId(undefined);
            setConfirmOpen(false);
          },
        },
      );
    }
  }, [calculatedProviderKey, onDeleteProvider, deleteMutate, form]);

  const getProviderDetails = () => {
    if (provider === PROVIDER_TYPE.VERTEX_AI) {
      return <VertexAIProviderDetails form={form} />;
    }

    if (provider === PROVIDER_TYPE.BEDROCK) {
      return <BedrockProviderDetails form={form} isEdit={isEdit} />;
    }

    if (provider === PROVIDER_TYPE.CUSTOM) {
      return <CustomProviderDetails form={form} isEdit={isEdit} />;
    }

    return (
      <CloudAIProviderDetails
        provider={provider as PROVIDER_TYPE}
        form={form}
      />
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody className="max-h-[60vh] pr-6">
          <ExplainerDescription
            className="mb-4"
            {...EXPLAINERS_MAP[EXPLAINER_ID.why_do_i_need_an_ai_provider]}
          />
          <Form {...form}>
            <form
              className="flex flex-col gap-4 pb-4"
              onSubmit={form.handleSubmit(cloudConfigHandler)}
            >
              <ProviderGrid
                options={providerOptions}
                selectedProvider={selectedComposedProvider}
                onSelectProvider={handleProviderSelect}
                disabled={Boolean(providerKey)}
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
          <Button type="submit" onClick={form.handleSubmit(cloudConfigHandler)}>
            {buttonText}
          </Button>
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
