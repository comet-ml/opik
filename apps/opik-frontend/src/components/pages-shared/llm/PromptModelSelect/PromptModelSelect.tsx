import React, { useCallback, useMemo, useRef, useState } from "react";
import isNull from "lodash/isNull";
import pick from "lodash/pick";

import { PROVIDERS } from "@/constants/providers";

import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectSeparator,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Button } from "@/components/ui/button";
import { ChevronRight, Search } from "lucide-react";
import { Input } from "@/components/ui/input";

import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { cn } from "@/lib/utils";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import ManageAIProviderDialog from "@/components/pages-shared/llm/ManageAIProviderDialog/ManageAIProviderDialog";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";

interface PromptModelSelectProps {
  value: PROVIDER_MODEL_TYPE | "";
  workspaceName: string;
  onChange: (value: PROVIDER_MODEL_TYPE, provider: PROVIDER_TYPE) => void;
  hasError?: boolean;
  provider: PROVIDER_TYPE | "";
  onAddProvider?: (provider: PROVIDER_TYPE) => void;
  onDeleteProvider?: (provider: PROVIDER_TYPE) => void;
}

const STALE_TIME = 1000;

const PromptModelSelect = ({
  value,
  workspaceName,
  onChange,
  hasError,
  provider,
  onAddProvider,
  onDeleteProvider,
}: PromptModelSelectProps) => {
  const resetDialogKeyRef = useRef(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const modelProviderMapRef = useRef<Record<string, PROVIDER_TYPE>>({});

  const [openConfigDialog, setOpenConfigDialog] = React.useState(false);
  const [filterValue, setFilterValue] = useState("");
  const [openProviderMenu, setOpenProviderMenu] = useState<string | null>(null);
  const { getProviderModels } = useLLMProviderModelsData();

  const { data } = useProviderKeys(
    {
      workspaceName,
    },
    {
      staleTime: STALE_TIME,
    },
  );

  const configuredProvidersList = useMemo(
    () => data?.content ?? [],
    [data?.content],
  );

  const groupOptions = useMemo(() => {
    const allProviderModels = getProviderModels();

    // Build a map of configured provider keys
    // For standard providers: use provider type directly
    // For custom providers: create dynamic keys like "custom-llm:ollama"
    const configuredProviderKeys = new Set<string>();
    configuredProvidersList.forEach((p) => {
      if (p.provider === PROVIDER_TYPE.CUSTOM) {
        // For custom providers, add dynamic key
        const providerKey = p.provider_name
          ? `${PROVIDER_TYPE.CUSTOM}:${p.provider_name}`
          : PROVIDER_TYPE.CUSTOM;
        configuredProviderKeys.add(providerKey);
      } else {
        configuredProviderKeys.add(p.provider);
      }
    });

    // Filter models by configured provider keys
    const filteredByConfiguredProviders = pick(
      allProviderModels,
      Array.from(configuredProviderKeys),
    );

    // Build model-to-provider mapping
    Object.entries(filteredByConfiguredProviders).forEach(
      ([providerKey, providerModels]) => {
        providerModels.forEach(({ value }) => {
          // For custom providers, map to the base PROVIDER_TYPE.CUSTOM
          const mappedProvider = providerKey.startsWith(
            `${PROVIDER_TYPE.CUSTOM}:`,
          )
            ? PROVIDER_TYPE.CUSTOM
            : (providerKey as PROVIDER_TYPE);
          modelProviderMapRef.current[value] = mappedProvider;
        });
      },
    );

    return Object.entries(filteredByConfiguredProviders)
      .map(([providerKey, providerModels]) => {
        const options = providerModels.map((providerModel) => ({
          label: providerModel.label,
          value: providerModel.value,
        }));

        if (!options.length) {
          return null;
        }

        // Handle custom providers with dynamic labels
        if (providerKey.startsWith(`${PROVIDER_TYPE.CUSTOM}:`)) {
          const customProviderName = providerKey.substring(
            `${PROVIDER_TYPE.CUSTOM}:`.length,
          );
          // Find the display name from the configured provider
          const customConfig = configuredProvidersList.find(
            (p) =>
              p.provider === PROVIDER_TYPE.CUSTOM &&
              p.provider_name === customProviderName,
          );
          const displayName = customConfig?.keyName || customProviderName;

          return {
            label: `${displayName} (Custom)`,
            options,
            icon: PROVIDERS[PROVIDER_TYPE.CUSTOM].icon,
            provider: providerKey as PROVIDER_TYPE,
          };
        }

        // Handle standard providers and legacy custom provider
        const providerType = providerKey as PROVIDER_TYPE;
        return {
          label: PROVIDERS[providerType].label,
          options,
          icon: PROVIDERS[providerType].icon,
          provider: providerType,
        };
      })
      .filter((g): g is NonNullable<typeof g> => !isNull(g));
  }, [configuredProvidersList, getProviderModels]);

  const filteredOptions = useMemo(() => {
    if (filterValue === "") {
      return groupOptions;
    }

    const loweredFilterValue = filterValue.toLowerCase();

    return groupOptions
      .map((groupOption) => {
        const filteredChildOptions = groupOption.options.filter(
          (o) =>
            o.label.toLowerCase().includes(loweredFilterValue) ||
            o.value.toLowerCase().includes(loweredFilterValue),
        );

        if (filteredChildOptions.length === 0) {
          return null;
        }

        return {
          ...groupOption,
          options: filteredChildOptions,
        };
      })
      .filter((filteredGroupedOption) => !isNull(filteredGroupedOption));
  }, [filterValue, groupOptions]);

  const getProviderIcon = useCallback((providerType: PROVIDER_TYPE | "") => {
    if (!providerType) {
      return null;
    }

    // Handle icon for dynamic custom providers
    if (providerType.startsWith(`${PROVIDER_TYPE.CUSTOM}:`)) {
      // For dynamic custom providers, use the custom provider icon
      return PROVIDERS[PROVIDER_TYPE.CUSTOM].icon;
    }

    // For standard providers, use their specific icon
    return PROVIDERS[providerType].icon;
  }, []);

  const getProviderLabel = useCallback(
    (providerType: PROVIDER_TYPE | "") => {
      if (!providerType) {
        return "";
      }

      // Handle label for dynamic custom providers
      if (providerType.startsWith(`${PROVIDER_TYPE.CUSTOM}:`)) {
        // For dynamic custom providers, get the label from groupOptions
        const customGroup = groupOptions.find(
          (o) => o.provider === providerType,
        );
        return customGroup ? customGroup.label : "";
      }

      // For standard providers, use PROVIDERS constant
      return PROVIDERS[providerType].label;
    },
    [groupOptions],
  );

  const handleOnChange = useCallback(
    (value: PROVIDER_MODEL_TYPE) => {
      const modelProvider = openProviderMenu
        ? (openProviderMenu as PROVIDER_TYPE)
        : modelProviderMapRef.current[value];
      onChange(value, modelProvider);
    },
    [onChange, openProviderMenu],
  );

  const handleSelectOpenChange = useCallback((open: boolean) => {
    if (!open) {
      setFilterValue("");
      setOpenProviderMenu(null);
    }
  }, []);

  const handleKeyDown = (event: React.KeyboardEvent) => {
    if (event.key.length === 1) {
      event.preventDefault();
      setFilterValue((filterValue) => `${filterValue}${event.key}`);
    }

    inputRef.current?.focus();
  };

  const renderOptions = () => {
    if (configuredProvidersList?.length === 0) {
      return (
        <div className="comet-body-s flex h-20 items-center justify-center text-muted-slate">
          No configured providers
        </div>
      );
    }

    if (filteredOptions.length === 0 && filterValue !== "") {
      return (
        <div className="comet-body-s flex h-20 items-center justify-center text-muted-slate">
          No search results
        </div>
      );
    }

    if (filterValue !== "") {
      return filteredOptions.map((groupOption) => {
        return (
          <SelectGroup key={groupOption?.label}>
            <SelectLabel className="h-10">{groupOption?.label}</SelectLabel>
            {groupOption?.options?.map((option) => (
              <SelectItem
                key={option.value}
                value={option.value!}
                className="h-10 justify-center"
              >
                {option.label}
              </SelectItem>
            ))}
          </SelectGroup>
        );
      });
    }

    return (
      <div>
        {groupOptions.map((group) => (
          <Popover key={group.label} open={group.provider === openProviderMenu}>
            <PopoverTrigger asChild>
              <div
                key={group.label}
                onMouseEnter={() => setOpenProviderMenu(group.provider)}
                onMouseLeave={() => setOpenProviderMenu(null)}
                className={cn(
                  "comet-body-s flex h-10 w-full items-center rounded-sm p-0 pl-2 hover:bg-primary-foreground justify-center",
                  {
                    "bg-primary-foreground":
                      group.provider === openProviderMenu,
                  },
                )}
              >
                {<group.icon className="comet-body mr-1" />}
                {group.label}
                <ChevronRight className="ml-auto mr-3 size-4 text-light-slate" />
              </div>
            </PopoverTrigger>

            <PopoverContent
              side="right"
              align="start"
              className="max-h-[400px] overflow-y-auto p-1"
              sideOffset={-5}
              onMouseEnter={() => setOpenProviderMenu(group.provider)}
              hideWhenDetached
            >
              {group.options.map((option) => {
                return (
                  <SelectItem
                    key={option.value}
                    value={option.value}
                    className="flex h-10 justify-center pr-5 focus:bg-primary-foreground focus:text-foreground"
                  >
                    {option.label}
                  </SelectItem>
                );
              })}
            </PopoverContent>
          </Popover>
        ))}
      </div>
    );
  };

  const renderProviderValueIcon = () => {
    const Icon = getProviderIcon(provider);

    if (!Icon) {
      return null;
    }

    return <Icon className="min-w-3.5 text-foreground" />;
  };

  const renderSelectTrigger = () => {
    const modelName =
      groupOptions
        .find((o) => o.provider === provider)
        ?.options?.find((m) => m.value === value)?.label ?? value;

    const providerLabel = getProviderLabel(provider);
    const title = providerLabel ? `${providerLabel} ${modelName}` : modelName;

    return (
      <SelectTrigger
        className={cn("size-full data-[placeholder]:text-light-slate", {
          "border-destructive": hasError,
        })}
      >
        <SelectValue
          placeholder="Select an LLM model"
          data-testid="select-a-llm-model"
        >
          <div className="flex items-center gap-2">
            {renderProviderValueIcon()}
            <span className="truncate">{title}</span>
          </div>
        </SelectValue>
      </SelectTrigger>
    );
  };

  return (
    <>
      <Select
        value={value || ""}
        onValueChange={handleOnChange}
        onOpenChange={handleSelectOpenChange}
      >
        {renderSelectTrigger()}
        <SelectContent onKeyDown={handleKeyDown} className="p-0">
          <div className="flex h-full flex-col">
            <div className="relative flex h-10 items-center justify-center gap-1 pl-6">
              <Search className="absolute left-2 size-4 text-light-slate" />
              <Input
                ref={inputRef}
                className="outline-0"
                placeholder="Search model"
                value={filterValue}
                variant="ghost"
                onChange={(e) => setFilterValue(e.target.value)}
              />
            </div>
            <SelectSeparator />
            <div className="flex-1 overflow-y-auto">{renderOptions()}</div>
            <SelectSeparator />
            <Button
              variant="link"
              className="h-10 w-full"
              onClick={() => {
                resetDialogKeyRef.current += 1;
                setOpenConfigDialog(true);
              }}
            >
              Manage AI providers
            </Button>
          </div>
        </SelectContent>
      </Select>
      <ManageAIProviderDialog
        key={resetDialogKeyRef.current}
        configuredProvidersList={configuredProvidersList}
        open={openConfigDialog}
        setOpen={setOpenConfigDialog}
        onAddProvider={onAddProvider}
        onDeleteProvider={onDeleteProvider}
      />
    </>
  );
};

export default PromptModelSelect;
