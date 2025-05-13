import React, { useCallback, useEffect, useMemo } from "react";
import { useForm, UseFormReturn } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import get from "lodash/get";
import isFunction from "lodash/isFunction";
import isArray from "lodash/isArray";

import { cn } from "@/lib/utils";
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
import {
  LocalAIProviderData,
  PROVIDER_LOCATION_TYPE,
  PROVIDER_TYPE,
  ProviderKey,
} from "@/types/providers";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import useProviderKeysUpdateMutation from "@/api/provider-keys/useProviderKeysUpdateMutation";
import useProviderKeysCreateMutation from "@/api/provider-keys/useProviderKeysCreateMutation";
import useLocalAIProviderData from "@/hooks/useLocalAIProviderData";
import { PROVIDERS, PROVIDERS_OPTIONS } from "@/constants/providers";
import { SelectItem } from "@/components/ui/select";
import { DropdownOption } from "@/types/shared";
import {
  AIProviderFormSchema,
  AIProviderFormType,
} from "@/components/shared/AddEditAIProviderDialog/schema";
import CloudAIProviderDetails from "@/components/shared/AddEditAIProviderDialog/CloudAIProviderDetails";
import LocalAIProviderDetails from "@/components/shared/AddEditAIProviderDialog/LocalAIProviderDetails";
import VertexAIProviderDetails from "@/components/shared/AddEditAIProviderDialog/VertexAIProviderDetails";

type AddEditAIProviderDialogProps = {
  providerKey?: ProviderKey;
  open: boolean;
  setOpen: (open: boolean) => void;
  onAddProvider?: (provider: PROVIDER_TYPE) => void;
  excludedProviders?: PROVIDER_TYPE[];
};

const AddEditAIProviderDialog: React.FC<AddEditAIProviderDialogProps> = ({
  providerKey,
  open,
  setOpen,
  onAddProvider,
  excludedProviders,
}) => {
  const { getLocalAIProviderData, setLocalAIProviderData } =
    useLocalAIProviderData();
  const { mutate: createMutate } = useProviderKeysCreateMutation();
  const { mutate: updateMutate } = useProviderKeysUpdateMutation();

  const localData = useMemo(() => {
    return providerKey?.provider
      ? getLocalAIProviderData(providerKey.provider)
      : undefined;
  }, [getLocalAIProviderData, providerKey]);

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

  const isEdit = Boolean(providerKey);
  const isCloudProvider =
    provider === "" ||
    PROVIDERS[provider]?.locationType === PROVIDER_LOCATION_TYPE.cloud;

  const title = isEdit
    ? "Edit AI provider configuration"
    : "Add AI provider configuration";

  const buttonText = isEdit ? "Update configuration" : "Save configuration";

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

    if (isEdit) {
      updateMutate({
        providerKey: {
          id: providerKey!.id,
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
    isEdit,
    provider,
    setOpen,
    updateMutate,
    providerKey,
    onAddProvider,
    createMutate,
  ]);

  const onSubmit = useCallback(
    () => (isCloudProvider ? cloudConfigHandler() : localConfigHandler()),
    [isCloudProvider, cloudConfigHandler, localConfigHandler],
  );

  const options = useMemo(() => {
    return isArray(excludedProviders)
      ? PROVIDERS_OPTIONS.filter(
          ({ value }) => !excludedProviders.includes(value),
        )
      : PROVIDERS_OPTIONS;
  }, [excludedProviders]);

  const renderOption = (option: DropdownOption<string>) => {
    const Icon = PROVIDERS[option.value as PROVIDER_TYPE]?.icon;

    return (
      <SelectItem
        key={option.value}
        value={option.value}
        description={<div className="pl-6">{option.description}</div>}
        withoutCheck
      >
        <div className="flex items-center gap-2">
          <Icon />
          {option.label}
        </div>
      </SelectItem>
    );
  };

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
          <DialogTitle>{title}</DialogTitle>
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
                      <SelectBox
                        disabled={isEdit}
                        renderOption={renderOption}
                        value={field.value}
                        onChange={(v) => {
                          const p = v as PROVIDER_TYPE;
                          form.setValue(
                            "locationType",
                            PROVIDERS[p].locationType,
                          );
                          field.onChange(p);
                        }}
                        options={options}
                        placeholder="Select a provider"
                        className={cn({
                          "border-destructive": Boolean(
                            validationErrors?.message,
                          ),
                        })}
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                );
              }}
            />
            {getProviderDetails()}
          </form>
        </Form>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button type="submit" onClick={form.handleSubmit(onSubmit)}>
            {buttonText}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditAIProviderDialog;
