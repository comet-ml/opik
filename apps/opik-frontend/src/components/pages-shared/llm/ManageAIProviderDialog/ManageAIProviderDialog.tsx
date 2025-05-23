import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useForm, UseFormReturn } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { MessageCircleWarning } from "lucide-react";
import { z } from "zod";
import get from "lodash/get";
import isFunction from "lodash/isFunction";

import { Button } from "@/components/ui/button";
import {
  Dialog,
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
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import {
  LocalAIProviderData,
  PROVIDER_LOCATION_TYPE,
  PROVIDER_TYPE,
  ProviderKey,
} from "@/types/providers";

import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useProviderKeysDeleteMutation from "@/api/provider-keys/useProviderKeysDeleteMutation";
import ProviderSelect from "@/components/pages-shared/llm/ProviderSelect/ProviderSelect";
import useProviderKeysUpdateMutation from "@/api/provider-keys/useProviderKeysUpdateMutation";
import useProviderKeysCreateMutation from "@/api/provider-keys/useProviderKeysCreateMutation";
import useLocalAIProviderData from "@/hooks/useLocalAIProviderData";
import { PROVIDERS } from "@/constants/providers";
import {
  AIProviderFormSchema,
  AIProviderFormType,
} from "@/components/pages-shared/llm/ManageAIProviderDialog/schema";
import CloudAIProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/CloudAIProviderDetails";
import LocalAIProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/LocalAIProviderDetails";
import VertexAIProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/VertexAIProviderDetails";

type ManageAIProviderDialogProps = {
  providerKey?: ProviderKey;
  open: boolean;
  setOpen: (open: boolean) => void;
  onAddProvider?: (provider: PROVIDER_TYPE) => void;
  onDeleteProvider?: (provider: PROVIDER_TYPE) => void;
  configuredProvidersList?: ProviderKey[];
};

const ManageAIProviderDialog: React.FC<ManageAIProviderDialogProps> = ({
  providerKey,
  open,
  setOpen,
  onAddProvider,
  onDeleteProvider,
  configuredProvidersList,
}) => {
  const [confirmOpen, setConfirmOpen] = useState<boolean>(false);

  const { getLocalAIProviderData, setLocalAIProviderData } =
    useLocalAIProviderData();
  const { mutate: createMutate } = useProviderKeysCreateMutation();
  const { mutate: updateMutate } = useProviderKeysUpdateMutation();
  const { mutate: deleteMutate } = useProviderKeysDeleteMutation();
  const { deleteLocalAIProviderData } = useLocalAIProviderData();

  const [localData, setLocalData] = useState<LocalAIProviderData | undefined>(
    providerKey?.provider
      ? getLocalAIProviderData(providerKey.provider)
      : undefined,
  );

  const form: UseFormReturn<AIProviderFormType> = useForm<
    z.infer<typeof AIProviderFormSchema>
  >({
    resolver: zodResolver(AIProviderFormSchema),
    defaultValues: {
      provider: providerKey?.provider || "",
      locationType: providerKey?.provider
        ? PROVIDERS[providerKey.provider]?.locationType
        : PROVIDER_LOCATION_TYPE.cloud,
      apiKey: "",
      url: localData?.url ?? "",
      models: localData?.models ?? "",
      location: undefined,
    },
  });

  const provider = form.watch("provider") as PROVIDER_TYPE | "";

  const configuredProviderKeys = useMemo(
    () => (configuredProvidersList || []).map((p) => p.provider),
    [configuredProvidersList],
  );

  const calculatedProviderKey = useMemo(() => {
    return configuredProvidersList?.find((p) => provider === p.provider);
  }, [configuredProvidersList, provider]);

  useEffect(() => {
    if (
      calculatedProviderKey &&
      PROVIDERS[calculatedProviderKey.provider]?.locationType ===
        PROVIDER_LOCATION_TYPE.local
    ) {
      const ld = getLocalAIProviderData(calculatedProviderKey.provider);
      if (ld) {
        setLocalData(ld);
        form.setValue("url", ld.url);
        form.setValue("models", ld.models);
      }
    }
  }, [calculatedProviderKey, form, getLocalAIProviderData]);

  const isCloudProvider =
    provider === "" ||
    PROVIDERS[provider]?.locationType === PROVIDER_LOCATION_TYPE.cloud;

  const isConfiguredProvider = Boolean(calculatedProviderKey);

  const buttonText = provider
    ? providerKey || calculatedProviderKey
      ? "Update configuration"
      : "Add configuration"
    : "Done";

  const localConfigHandler = useCallback(() => {
    const data: LocalAIProviderData = {
      created_at: new Date().toISOString(),
      ...localData,
      url: form.getValues("url"),
      models: form.getValues("models"),
    };

    if (provider) {
      setLocalAIProviderData(provider, data);
      if (isFunction(onAddProvider)) {
        onAddProvider(provider);
      }
    }

    setOpen(false);
  }, [
    localData,
    form,
    provider,
    setOpen,
    setLocalAIProviderData,
    onAddProvider,
  ]);

  const cloudConfigHandler = useCallback(() => {
    const apiKey = form.getValues("apiKey");
    const location = form.getValues("location");
    const isVertex = provider === PROVIDER_TYPE.VERTEX_AI;

    if (providerKey || calculatedProviderKey) {
      updateMutate({
        providerKey: {
          id: providerKey?.id ?? calculatedProviderKey?.id,
          apiKey,
          location: isVertex ? location : undefined,
        },
      });
    } else if (provider) {
      if (isFunction(onAddProvider)) {
        onAddProvider(provider);
      }

      createMutate({
        providerKey: {
          apiKey,
          provider,
          location: isVertex ? location : undefined,
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

  const onSubmit = useCallback(
    () => (isCloudProvider ? cloudConfigHandler() : localConfigHandler()),
    [isCloudProvider, cloudConfigHandler, localConfigHandler],
  );

  const deleteProviderKeyHandler = useCallback(() => {
    const config = PROVIDERS[provider as PROVIDER_TYPE];
    if (config.locationType === PROVIDER_LOCATION_TYPE.local) {
      deleteLocalAIProviderData(config.value);
    } else if (calculatedProviderKey) {
      deleteMutate({
        providerId: calculatedProviderKey.id,
      });
    }

    if (isFunction(onDeleteProvider)) {
      onDeleteProvider(provider as PROVIDER_TYPE);
    }
  }, [
    provider,
    calculatedProviderKey,
    onDeleteProvider,
    deleteLocalAIProviderData,
    deleteMutate,
  ]);

  const getProviderDetails = () => {
    if (provider === PROVIDER_TYPE.VERTEX_AI) {
      return <VertexAIProviderDetails form={form} />;
    }

    if (!isCloudProvider) {
      return <LocalAIProviderDetails provider={provider} form={form} />;
    }

    return <CloudAIProviderDetails provider={provider} form={form} />;
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Provider configuration</DialogTitle>
        </DialogHeader>
        <Form {...form}>
          <form
            className="flex flex-col gap-4 pb-4"
            onSubmit={form.handleSubmit(onSubmit)}
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
                        disabled={Boolean(providerKey)}
                        value={(field.value as PROVIDER_TYPE) || ""}
                        onChange={(v) => {
                          const p = v as PROVIDER_TYPE;

                          form.setValue(
                            "locationType",
                            PROVIDERS[p].locationType,
                          );
                          field.onChange(p);
                        }}
                        configuredProviderKeys={configuredProviderKeys}
                        hasError={Boolean(validationErrors?.message)}
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                );
              }}
            />
            {isConfiguredProvider && isCloudProvider && (
              <Alert>
                <MessageCircleWarning className="size-4" />
                <AlertTitle>Editing an existing key</AlertTitle>
                <AlertDescription>
                  A key is already set for this provider. Since AI provider
                  configurations are workspace-wide, adding a new key will
                  overwrite the existing one for everyone.
                </AlertDescription>
              </Alert>
            )}
            {getProviderDetails()}
          </form>
        </Form>
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
          <Button type="submit" onClick={form.handleSubmit(onSubmit)}>
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
      />
    </Dialog>
  );
};

export default ManageAIProviderDialog;
