import React, { useCallback, useMemo, useRef, useState } from "react";
import {
  PLAYGROUND_MODELS,
  PLAYGROUND_PROVIDERS,
} from "@/constants/playground";
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
import isNull from "lodash/isNull";

import {
  PLAYGROUND_MODEL_TYPE,
  PLAYGROUND_PROVIDERS_TYPES,
} from "@/types/playgroundPrompts";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { cn } from "@/lib/utils";

interface PromptModelSelectProps {
  value: PLAYGROUND_MODEL_TYPE | "";
  onChange: (value: PLAYGROUND_MODEL_TYPE) => void;
  provider: PLAYGROUND_PROVIDERS_TYPES | "";
}

// ALEX ALIGN POP UPS
// ALEX MAKE H-10 a variable
// ALEX MAKE gap-6 a variablex
// ALEX BREAK DOWN THE COMPONENT
const PromptModelSelect = ({
  value,
  onChange,
  provider,
}: PromptModelSelectProps) => {
  const inputRef = useRef<HTMLInputElement>(null);
  const [filterValue, setFilterValue] = useState("");
  const [openProviderMenu, setOpenProviderMenu] = useState<string | null>(null);

  const handleSelectOpenChange = useCallback((open: boolean) => {
    if (!open) {
      setFilterValue("");
      setOpenProviderMenu(null);
    }
  }, []);
  // ALEX
  const handleKeyDown = (event: React.KeyboardEvent) => {
    if (event.key.length === 1) {
      event.preventDefault();
      setFilterValue((filterValue) => `${filterValue}${event.key}`);
    }

    inputRef.current?.focus();
  };

  const groupOptions = useMemo(() => {
    return Object.entries(PLAYGROUND_MODELS).map(
      ([providerName, providerModels]) => {
        return {
          label: providerName,
          options: providerModels.map((providerModel) => ({
            label: providerModel.label,
            value: providerModel.value,
          })),
          icon: PLAYGROUND_PROVIDERS[providerName as PLAYGROUND_PROVIDERS_TYPES]
            .icon,
        };
      },
    );
  }, []);

  const handleOnChange = useCallback(
    (value: PLAYGROUND_MODEL_TYPE) => {
      onChange(value);
    },
    [onChange],
  );

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

  const renderOptions = () => {
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
            {groupOption?.options.map((option) => (
              <SelectItem
                key={option.value}
                value={option.value}
                className="h-10"
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
          <Popover key={group.label} open={group.label === openProviderMenu}>
            <PopoverTrigger asChild>
              <div
                key={group.label}
                onMouseEnter={() => setOpenProviderMenu(group.label)}
                onMouseLeave={() => setOpenProviderMenu(null)}
                className={cn(
                  "comet-body-s flex h-10 w-full items-center rounded-sm p-0 pl-2 hover:bg-primary-foreground",
                  {
                    "bg-primary-foreground": group.label === openProviderMenu,
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
              className="p-1"
              sideOffset={-5}
              onMouseEnter={() => setOpenProviderMenu(group.label)}
              hideWhenDetached
            >
              {group.options.map((option) => {
                return (
                  <SelectItem
                    key={option.value}
                    value={option.value}
                    className="flex h-10"
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
    if (!provider) {
      return null;
    }

    const Icon = PLAYGROUND_PROVIDERS[provider].icon;

    if (!Icon) {
      return null;
    }

    return <Icon />;
  };

  return (
    <Select
      value={value || ""}
      onValueChange={handleOnChange}
      onOpenChange={handleSelectOpenChange}
    >
      <SelectTrigger className="size-full data-[placeholder]:text-light-slate">
        <SelectValue
          placeholder="Select a LLM model"
          data-testid="select-a-llm-model"
        >
          <div className="flex items-center gap-2">
            {renderProviderValueIcon()}
            {provider} {value}
          </div>
        </SelectValue>
      </SelectTrigger>
      <SelectContent onKeyDown={handleKeyDown} className="p-0">
        <div className="relative flex h-10 items-center gap-1 pl-6">
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
        {renderOptions()}
        <SelectSeparator />
        <Button variant="link" className="size-full">
          Add configuration
        </Button>
      </SelectContent>
    </Select>
  );
};

export default PromptModelSelect;
