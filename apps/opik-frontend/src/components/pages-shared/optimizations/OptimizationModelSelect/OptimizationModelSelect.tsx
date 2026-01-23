import React, { useMemo, useRef, useState } from "react";
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
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Input } from "@/components/ui/input";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { cn } from "@/lib/utils";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { PROVIDER_MODELS } from "@/hooks/useLLMProviderModelsData";
import { PROVIDERS } from "@/constants/providers";
import { OPTIMIZATION_STUDIO_SUPPORTED_MODELS } from "@/constants/optimizations";

const SUPPORTED_PROVIDERS = [
  PROVIDER_TYPE.OPEN_AI,
  PROVIDER_TYPE.ANTHROPIC,
] as const;

interface OptimizationModelSelectProps {
  value: PROVIDER_MODEL_TYPE | "";
  onChange: (value: PROVIDER_MODEL_TYPE) => void;
  hasError?: boolean;
  disabled?: boolean;
}

// it's different from PromptModelSelect because it's not configurable, we ALWAYS have these providers
const OptimizationModelSelect: React.FC<OptimizationModelSelectProps> = ({
  value,
  onChange,
  hasError,
  disabled = false,
}) => {
  const inputRef = useRef<HTMLInputElement>(null);
  const [filterValue, setFilterValue] = useState("");
  const [openProviderMenu, setOpenProviderMenu] = useState<string | null>(null);

  const groupOptions = useMemo(() => {
    return SUPPORTED_PROVIDERS.map((providerType) => {
      const providerConfig = PROVIDERS[providerType];
      const allModels = PROVIDER_MODELS[providerType];
      const supportedModelValues =
        OPTIMIZATION_STUDIO_SUPPORTED_MODELS[providerType] || [];

      const filteredModels = supportedModelValues
        .map((modelValue) => allModels.find((m) => m.value === modelValue))
        .filter(Boolean) as { value: PROVIDER_MODEL_TYPE; label: string }[];

      return {
        providerType,
        label: providerConfig.label,
        icon: providerConfig.icon,
        options: filteredModels.map((m) => ({
          label: m.label,
          value: m.value,
        })),
      };
    });
  }, []);

  const filteredOptions = useMemo(() => {
    if (!filterValue) return groupOptions;

    const search = filterValue.toLowerCase();
    return groupOptions
      .map((group) => ({
        ...group,
        options: group.options.filter(
          (o) =>
            o.label.toLowerCase().includes(search) ||
            o.value.toLowerCase().includes(search),
        ),
      }))
      .filter((group) => group.options.length > 0);
  }, [filterValue, groupOptions]);

  const selectedGroup = useMemo(() => {
    if (!value) return null;
    return groupOptions.find((g) => g.options.some((o) => o.value === value));
  }, [value, groupOptions]);

  const handleOpenChange = (open: boolean) => {
    if (!open) {
      setFilterValue("");
      setOpenProviderMenu(null);
    }
  };

  const handleKeyDown = (event: React.KeyboardEvent) => {
    if (event.key.length === 1) {
      event.preventDefault();
      setFilterValue((prev) => prev + event.key);
    }
    inputRef.current?.focus();
  };

  const selectedModelLabel =
    selectedGroup?.options.find((o) => o.value === value)?.label ?? value;
  const displayTitle = selectedGroup
    ? `${selectedGroup.label} ${selectedModelLabel}`
    : selectedModelLabel;

  return (
    <Select
      value={value || ""}
      onValueChange={onChange}
      onOpenChange={handleOpenChange}
      disabled={disabled}
    >
      <TooltipWrapper content={displayTitle}>
        <SelectTrigger
          className={cn("size-full data-[placeholder]:text-light-slate", {
            "border-destructive": hasError,
          })}
        >
          <SelectValue placeholder="Select an LLM model">
            <div className="flex items-center gap-2">
              {selectedGroup && (
                <selectedGroup.icon className="min-w-3.5 text-foreground" />
              )}
              <span className="truncate">{displayTitle}</span>
            </div>
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

          <div className="flex-1 overflow-y-auto">
            {filteredOptions.length === 0 ? (
              <div className="comet-body-s flex h-20 items-center justify-center text-muted-slate">
                No search results
              </div>
            ) : filterValue ? (
              filteredOptions.map((group) => (
                <SelectGroup key={group.providerType}>
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
              ))
            ) : (
              <div>
                {groupOptions.map((group) => (
                  <Popover
                    key={group.providerType}
                    open={group.providerType === openProviderMenu}
                  >
                    <PopoverTrigger asChild>
                      <div
                        onMouseEnter={() =>
                          setOpenProviderMenu(group.providerType)
                        }
                        onMouseLeave={() => setOpenProviderMenu(null)}
                        className={cn(
                          "comet-body-s flex h-10 w-full cursor-pointer items-center justify-center rounded-sm p-0 pl-2 hover:bg-primary-foreground",
                          {
                            "bg-primary-foreground":
                              group.providerType === openProviderMenu,
                          },
                        )}
                      >
                        <group.icon className="comet-body mr-1" />
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
                        setOpenProviderMenu(group.providerType)
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
            )}
          </div>
        </div>
      </SelectContent>
    </Select>
  );
};

export default OptimizationModelSelect;
