import React, { useCallback, useMemo, useRef, useState } from "react";

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
import { ChevronRight, Search } from "lucide-react";
import { ListAction } from "@/components/ui/list-action";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Input } from "@/components/ui/input";

import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { cn } from "@/lib/utils";
import {
  PROVIDER_MODEL_TYPE,
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import ManageAIProviderDialog from "@/components/pages-shared/llm/ManageAIProviderDialog/ManageAIProviderDialog";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import { parseComposedProviderType } from "@/lib/provider";
import { useModelOptions } from "./useModelOptions";

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
  disabled?: boolean;
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
  disabled = false,
}: PromptModelSelectProps) => {
  const resetDialogKeyRef = useRef(0);
  const inputRef = useRef<HTMLInputElement>(null);

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

  const {
    freeModelOption,
    groupOptions,
    filteredFreeModel,
    filteredGroups,
    modelProviderMapRef,
  } = useModelOptions(configuredProvidersList, getProviderModels, filterValue);

  const handleOnChange = useCallback(
    (value: PROVIDER_MODEL_TYPE) => {
      const modelProvider = openProviderMenu
        ? openProviderMenu
        : modelProviderMapRef.current[value];
      onChange(value, parseComposedProviderType(modelProvider));
    },
    [onChange, openProviderMenu, modelProviderMapRef],
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

  // Get selected model info for trigger display
  const getSelectedModelInfo = useCallback(() => {
    if (
      freeModelOption &&
      provider === PROVIDER_TYPE.OPIK_FREE &&
      value === freeModelOption.value
    ) {
      return { icon: freeModelOption.icon, title: freeModelOption.label };
    }

    const selectedGroup = groupOptions.find(
      (o) => o.composedProviderType === provider,
    );
    const modelName =
      selectedGroup?.options?.find((m) => m.value === value)?.label ?? value;

    return {
      icon: selectedGroup?.icon,
      title: `${
        selectedGroup?.label ? selectedGroup.label + " " : ""
      }${modelName}`,
    };
  }, [freeModelOption, provider, value, groupOptions]);

  const renderOptions = () => {
    const hasNoProviders =
      !freeModelOption && configuredProvidersList?.length === 0;
    const hasNoResults =
      !filteredFreeModel && filteredGroups.length === 0 && filterValue !== "";

    if (hasNoProviders) {
      return (
        <div className="comet-body-s flex h-20 items-center justify-center text-muted-slate">
          No configured providers
        </div>
      );
    }

    if (hasNoResults) {
      return (
        <div className="comet-body-s flex h-20 items-center justify-center text-muted-slate">
          No search results
        </div>
      );
    }

    // When searching, show flat list
    if (filterValue !== "") {
      const FreeModelIcon = filteredFreeModel?.icon;
      return (
        <>
          {/* Free model shown as single clickable row when searching */}
          {filteredFreeModel && (
            <SelectItem
              value={filteredFreeModel.value}
              withoutCheck
              className="comet-body-s h-10 justify-center hover:bg-primary-foreground"
            >
              <div className="flex w-full items-center gap-2">
                {FreeModelIcon && <FreeModelIcon className="size-4 shrink-0" />}
                <span>{filteredFreeModel.label}</span>
              </div>
            </SelectItem>
          )}
          {filteredGroups.map((groupOption) => {
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
          })}
        </>
      );
    }

    const FreeModelIconNormal = freeModelOption?.icon;
    return (
      <div>
        {/* Free model shown as single clickable row - matches provider row styling */}
        {freeModelOption && (
          <SelectItem
            value={freeModelOption.value}
            withoutCheck
            className="comet-body-s h-10 justify-center hover:bg-primary-foreground"
          >
            <div className="flex w-full items-center gap-2">
              {FreeModelIconNormal && (
                <FreeModelIconNormal className="size-4 shrink-0" />
              )}
              <span>{freeModelOption.label}</span>
            </div>
          </SelectItem>
        )}

        {/* Other providers with dropdown submenus */}
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
                  "comet-body-s flex h-10 w-full items-center gap-2 rounded-sm p-0 pl-2 hover:bg-primary-foreground",
                  {
                    "bg-primary-foreground":
                      group.composedProviderType === openProviderMenu,
                  },
                )}
              >
                <group.icon className="size-4 shrink-0" />
                <span>{group.label}</span>
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
    const { icon: Icon, title } = getSelectedModelInfo();

    return (
      <TooltipWrapper content={title}>
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
              {Icon && <Icon className="size-4 shrink-0 text-foreground" />}
              <span className="truncate">{title}</span>
            </div>
          </SelectValue>
        </SelectTrigger>
      </TooltipWrapper>
    );
  };

  return (
    <>
      <Select
        value={value || ""}
        onValueChange={handleOnChange}
        onOpenChange={handleSelectOpenChange}
        disabled={disabled}
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
            <div className="flex-1 overflow-y-auto py-1">{renderOptions()}</div>
            <SelectSeparator />
            <ListAction
              onClick={() => {
                resetDialogKeyRef.current += 1;
                setOpenConfigDialog(true);
              }}
            >
              Manage AI providers
            </ListAction>
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
