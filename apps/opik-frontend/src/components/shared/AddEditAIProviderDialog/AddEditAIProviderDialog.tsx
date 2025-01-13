import React, { useCallback, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { ProviderKey, PROVIDER_TYPE } from "@/types/providers";
import useProviderKeysUpdateMutation from "@/api/provider-keys/useProviderKeysUpdateMutation";
import useProviderKeysCreateMutation from "@/api/provider-keys/useProviderKeysCreateMutation";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { PROVIDERS, PROVIDERS_OPTIONS } from "@/constants/providers";
import { SelectItem } from "@/components/ui/select";
import { DropdownOption } from "@/types/shared";
import EyeInput from "@/components/shared/EyeInput/EyeInput";
import isFunction from "lodash/isFunction";

type AddEditAIProviderDialogProps = {
  providerKey?: ProviderKey;
  open: boolean;
  setOpen: (open: boolean) => void;
  onAddProvider?: (provider: PROVIDER_TYPE) => void;
};

const AddEditAIProviderDialog: React.FC<AddEditAIProviderDialogProps> = ({
  providerKey,
  open,
  setOpen,
  onAddProvider,
}) => {
  const { mutate: createMutate } = useProviderKeysCreateMutation();
  const { mutate: updateMutate } = useProviderKeysUpdateMutation();
  const [provider, setProvider] = useState<PROVIDER_TYPE | "">(
    providerKey?.provider || "",
  );
  const [apiKey, setApiKey] = useState("");

  const isEdit = Boolean(providerKey);
  const isValid = Boolean(apiKey.length);

  const providerName = (provider && PROVIDERS[provider].label) || "";

  const title = isEdit
    ? "Edit AI provider configuration"
    : "Add AI provider configuration";

  const buttonText = isEdit ? "Update configuration" : "Save configuration";

  const apiKeyLabel = provider ? `${providerName} API Key` : "API Key";

  const submitHandler = useCallback(() => {
    if (isEdit) {
      updateMutate({
        providerKey: {
          id: providerKey!.id,
          apiKey,
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
        },
      });
    }
  }, [
    createMutate,
    isEdit,
    apiKey,
    updateMutate,
    provider,
    providerKey,
    onAddProvider,
  ]);

  const renderOption = (option: DropdownOption<string>) => {
    const Icon = PROVIDERS[option.value as PROVIDER_TYPE].icon;

    return (
      <SelectItem key={option.value} value={option.value} withoutCheck>
        <div className="flex items-center gap-2">
          <Icon />
          {option.label}
        </div>
      </SelectItem>
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-2 pb-4">
          <Label>Provider</Label>
          <SelectBox
            disabled={isEdit}
            renderOption={renderOption}
            value={provider}
            onChange={(v) => setProvider(v as PROVIDER_TYPE)}
            options={PROVIDERS_OPTIONS}
            placeholder="Select a provider"
          />
        </div>
        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="apiKey">{apiKeyLabel}</Label>
          <EyeInput
            id="apiKey"
            placeholder={apiKeyLabel}
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
          />
          {provider && (
            <span className="comet-body-s mt-1 text-light-slate">
              Get your {providerName} API key{" "}
              <Button variant="link" size="sm" asChild className="px-0">
                <a
                  href={PROVIDERS[provider].apiKeyURL}
                  target="_blank"
                  rel="noreferrer"
                >
                  here
                </a>
              </Button>
              .
            </span>
          )}
        </div>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button type="submit" disabled={!isValid} onClick={submitHandler}>
              {buttonText}
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditAIProviderDialog;
