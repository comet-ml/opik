import React, { useCallback, useMemo, useRef, useState } from "react";
import { ChevronRight, Search, Settings2 } from "lucide-react";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectSeparator,
  SelectTrigger,
  SelectValue,
} from "@/ui/select";
import { ListAction } from "@/ui/list-action";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Input } from "@/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { cn } from "@/lib/utils";
import { COMPOSED_PROVIDER_TYPE, PROVIDER_MODEL_TYPE } from "@/types/providers";
import useAppStore from "@/store/AppStore";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import ManageAIProviderDialog from "@/v2/pages-shared/llm/ManageAIProviderDialog/ManageAIProviderDialog";
import { useModelOptions } from "@/v2/pages-shared/llm/PromptModelSelect/useModelOptions";
import { usePermissions } from "@/contexts/PermissionsContext";

interface OptimizationModelSelectProps {
  value: PROVIDER_MODEL_TYPE | "";
  onChange: (value: PROVIDER_MODEL_TYPE) => void;
  hasError?: boolean;
  disabled?: boolean;
}

const OptimizationModelSelect: React.FC<OptimizationModelSelectProps> = ({
  value,
  onChange,
  hasError,
  disabled = false,
}) => {
  const resetDialogKeyRef = useRef(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const [filterValue, setFilterValue] = useState("");
  const [openProviderMenu, setOpenProviderMenu] =
    useState<COMPOSED_PROVIDER_TYPE | null>(null);
  const [openConfigDialog, setOpenConfigDialog] = useState(false);

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { providerModels } = useLLMProviderModelsData();

  const {
    permissions: { canUpdateAIProviders },
  } = usePermissions();

  const { data } = useProviderKeys({ workspaceName }, { staleTime: 1000 });

  const configuredProvidersList = useMemo(
    () => data?.content ?? [],
    [data?.content],
  );

  const { freeModelOption, groupOptions, filteredFreeModel, filteredGroups } =
    useModelOptions(configuredProvidersList, providerModels, filterValue);

  const selectedInfo = useMemo(() => {
    if (!value) return null;

    if (freeModelOption && value === freeModelOption.value) {
      return {
        icon: freeModelOption.icon,
        label: freeModelOption.label,
        title: freeModelOption.label,
      };
    }

    for (const group of groupOptions) {
      const match = group.options.find((o) => o.value === value);
      if (match) {
        return {
          icon: group.icon,
          label: match.label,
          title: `${group.label} ${match.label}`,
        };
      }
    }
    return null;
  }, [value, freeModelOption, groupOptions]);

  const handleOpenChange = useCallback((open: boolean) => {
    if (!open) {
      setFilterValue("");
      setOpenProviderMenu(null);
    }
  }, []);

  const handleKeyDown = (event: React.KeyboardEvent) => {
    if (event.key.length === 1) {
      event.preventDefault();
      setFilterValue((prev) => prev + event.key);
    }
    inputRef.current?.focus();
  };

  const handleManageProviders = useCallback(() => {
    resetDialogKeyRef.current += 1;
    setOpenConfigDialog(true);
  }, []);

  const hasNoProviders =
    !freeModelOption && configuredProvidersList.length === 0;
  const hasNoResults =
    !filteredFreeModel && filteredGroups.length === 0 && filterValue !== "";

  const renderOptions = () => {
    if (hasNoProviders) {
      return (
        <div className="comet-body-s flex h-20 flex-col items-center justify-center gap-1 px-4 text-center text-muted-slate">
          <span>No AI providers configured yet.</span>
          {canUpdateAIProviders ? (
            <Button
              variant="link"
              size="sm"
              className="h-auto p-0"
              onClick={handleManageProviders}
            >
              Configure a provider
            </Button>
          ) : (
            <span>Ask a workspace admin to add one to choose a model.</span>
          )}
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

    if (filterValue !== "") {
      const FreeModelIcon = filteredFreeModel?.icon;
      return (
        <>
          {filteredFreeModel && (
            <SelectItem
              value={filteredFreeModel.value}
              className="comet-body-s h-10 justify-center hover:bg-primary-foreground"
            >
              <div className="flex w-full items-center gap-2">
                {FreeModelIcon && <FreeModelIcon className="size-4 shrink-0" />}
                <span>{filteredFreeModel.label}</span>
              </div>
            </SelectItem>
          )}
          {filteredGroups.map((group) => (
            <SelectGroup key={group.label}>
              <SelectLabel className="h-10">{group.label}</SelectLabel>
              {group.options.map((option) => (
                <SelectItem
                  key={option.value}
                  value={option.value}
                  className="h-10 justify-center"
                >
                  {option.label}
                </SelectItem>
              ))}
            </SelectGroup>
          ))}
        </>
      );
    }

    const FreeModelIconNormal = freeModelOption?.icon;
    return (
      <div>
        {freeModelOption && (
          <SelectItem
            value={freeModelOption.value}
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

        {groupOptions.map((group) => (
          <Popover
            key={group.label}
            open={group.composedProviderType === openProviderMenu}
          >
            <PopoverTrigger asChild>
              <div
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
              {group.options.map((option) => (
                <SelectItem
                  key={option.value}
                  value={option.value}
                  className="flex h-10 justify-center pr-5 focus:bg-primary-foreground focus:text-foreground"
                >
                  {option.label}
                </SelectItem>
              ))}
            </PopoverContent>
          </Popover>
        ))}
      </div>
    );
  };

  const displayTitle = selectedInfo?.title;
  const Icon = selectedInfo?.icon;

  return (
    <>
      <Select
        value={value || ""}
        onValueChange={onChange}
        onOpenChange={handleOpenChange}
        disabled={disabled}
      >
        <TooltipWrapper content={displayTitle ?? ""}>
          <SelectTrigger
            className={cn("size-full data-[placeholder]:text-light-slate", {
              "border-destructive": hasError,
            })}
          >
            <SelectValue placeholder="Select an LLM model">
              {displayTitle && (
                <div className="flex items-center gap-2">
                  {Icon && <Icon className="min-w-3.5 text-foreground" />}
                  <span className="truncate">{displayTitle}</span>
                </div>
              )}
            </SelectValue>
          </SelectTrigger>
        </TooltipWrapper>

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
            {canUpdateAIProviders && (
              <>
                <SelectSeparator />
                <ListAction onClick={handleManageProviders}>
                  <Settings2 className="size-3.5 shrink-0" />
                  Manage AI providers
                </ListAction>
              </>
            )}
          </div>
        </SelectContent>
      </Select>
      {canUpdateAIProviders && (
        <ManageAIProviderDialog
          key={resetDialogKeyRef.current}
          configuredProvidersList={configuredProvidersList}
          open={openConfigDialog}
          setOpen={setOpenConfigDialog}
        />
      )}
    </>
  );
};

export default OptimizationModelSelect;
