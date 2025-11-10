import React, { useCallback, useMemo, useRef, useState } from "react";
import isNull from "lodash/isNull";
import pick from "lodash/pick";

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
import { PROVIDER_MODEL_TYPE, COMPOSED_PROVIDER_TYPE } from "@/types/providers";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import ManageAIProviderDialog from "@/components/pages-shared/llm/ManageAIProviderDialog/ManageAIProviderDialog";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import {
  getProviderDisplayName,
  getProviderIcon,
  parseComposedProviderType,
} from "@/lib/provider";

interface PromptModelSelectProps {
  value: PROVIDER_MODEL_TYPE | "";
  workspaceName: string;
  onChange: (
    value: PROVIDER_MODEL_TYPE,
    provider: COMPOSED_PROVIDER_TYPE,
  ) => void;
  hasError?: boolean;
  provider: COMPOSED_PROVIDER_TYPE | "";
  onAddProvider?: (provider: COMPOSED_PROVIDER_TYPE) => void;
  onDeleteProvider?: (provider: COMPOSED_PROVIDER_TYPE) => void;
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
  const modelProviderMapRef = useRef<Record<string, COMPOSED_PROVIDER_TYPE>>(
    {},
  );

  const [openConfigDialog, setOpenConfigDialog] = React.useState(false);
  const [filterValue, setFilterValue] = useState("");
  const [openProviderMenu, setOpenProviderMenu] =
    useState<COMPOSED_PROVIDER_TYPE | null>(null);
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
    const filteredByConfiguredProviders = pick(
      getProviderModels(),
      configuredProvidersList.map((p) => p.ui_composed_provider),
    );

    Object.entries(filteredByConfiguredProviders).forEach(
      ([pn, providerModels]) => {
        providerModels.forEach(({ value }) => {
          modelProviderMapRef.current[value] = pn;
        });
      },
    );

    return Object.entries(filteredByConfiguredProviders)
      .map(([pn, providerModels]) => {
        const composedProviderType = pn as COMPOSED_PROVIDER_TYPE;
        const configuredProvider = configuredProvidersList.find(
          (p) => p.ui_composed_provider === composedProviderType,
        )!;

        const options = providerModels.map((providerModel) => ({
          label: providerModel.label,
          value: providerModel.value,
        }));

        if (!options.length) {
          return null;
        }

        return {
          label: getProviderDisplayName(configuredProvider),
          options,
          icon: getProviderIcon(configuredProvider),
          composedProviderType: composedProviderType,
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

  const handleOnChange = useCallback(
    (value: PROVIDER_MODEL_TYPE) => {
      const modelProvider = openProviderMenu
        ? openProviderMenu
        : modelProviderMapRef.current[value];
      onChange(value, parseComposedProviderType(modelProvider));
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
          <Popover
            key={group.label}
            open={group.composedProviderType === openProviderMenu}
          >
            <PopoverTrigger asChild>
              <div
                key={group.label}
                onMouseEnter={() =>
                  setOpenProviderMenu(group.composedProviderType)
                }
                onMouseLeave={() => setOpenProviderMenu(null)}
                className={cn(
                  "comet-body-s flex h-10 w-full items-center rounded-sm p-0 pl-2 hover:bg-primary-foreground justify-center",
                  {
                    "bg-primary-foreground":
                      group.composedProviderType === openProviderMenu,
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
              onMouseEnter={() =>
                setOpenProviderMenu(group.composedProviderType)
              }
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

  const renderSelectTrigger = () => {
    const selectedGroup = groupOptions.find(
      (o) => o.composedProviderType === provider,
    );
    const modelName =
      selectedGroup?.options?.find((m) => m.value === value)?.label ?? value;

    const title = `${
      selectedGroup ? selectedGroup.label + " " : ""
    }${modelName}`;

    const icon = selectedGroup?.icon ? (
      <selectedGroup.icon className="min-w-3.5 text-foreground" />
    ) : null;

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
            {icon}
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
