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
  const { mutate: createMutate } = useProviderKeysCreateMutation();
  const { mutate: updateMutate } = useProviderKeysUpdateMutation();
  const { mutate: deleteMutate } = useProviderKeysDeleteMutation();

  const form: UseFormReturn<AIProviderFormType> = useForm<
    z.infer<typeof AIProviderFormSchema>
  >({
    resolver: zodResolver(AIProviderFormSchema),
    defaultValues: {
      provider: providerKey?.provider || "",
      apiKey: "",
      // @ts-expect-error not to trigger type error when we have different schemas to different providers
      location: providerKey?.configuration?.location ?? "",
      url: providerKey?.base_url ?? "",
      models: convertCustomProviderModels(
        providerKey?.configuration?.models ?? "",
      ),
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

  const cloudConfigHandler = useCallback(() => {
    const apiKey = form.getValues("apiKey");
    const url = form.getValues("url");
    const location = form.getValues("location");
    const models = convertCustomProviderModels(
      form.getValues("models") ?? "",
      true,
    );
    const isVertex = provider === PROVIDER_TYPE.VERTEX_AI;
    const isCustom = provider === PROVIDER_TYPE.CUSTOM;

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
          ...(configuration && { configuration }),
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
          base_url: isCustom ? url : undefined,
          ...(configuration && { configuration }),
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
      deleteMutate({
        providerId: calculatedProviderKey.id,
      });
    }

    if (isFunction(onDeleteProvider)) {
      onDeleteProvider(provider as PROVIDER_TYPE);
    }
  }, [provider, calculatedProviderKey, onDeleteProvider, deleteMutate]);

  const getProviderDetails = () => {
    if (provider === PROVIDER_TYPE.VERTEX_AI) {
      return <VertexAIProviderDetails form={form} />;
    }

    if (provider === PROVIDER_TYPE.CUSTOM) {
      return <CustomProviderDetails form={form} />;
    }

    return <CloudAIProviderDetails provider={provider} form={form} />;
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
                          disabled={Boolean(providerKey)}
                          value={(field.value as PROVIDER_TYPE) || ""}
                          onChange={(v) => {
                            const p = v as PROVIDER_TYPE;
                            const providerData = configuredProvidersList?.find(
                              (c) => p === c.provider,
                            );

                            form.setValue("url", providerData?.base_url ?? "");
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
        description="This configuration is shared across the workspace. Deleting it will remove access for everyone. This action can’t be undone. Are you sure you want to proceed?"
        confirmText="Delete configuration"
        confirmButtonVariant="destructive"
      />
    </Dialog>
  );
};

export default ManageAIProviderDialog;
