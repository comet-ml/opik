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
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  PLAYGROUND_MODEL_TYPE,
  PLAYGROUND_PROVIDERS_TYPES,
} from "@/types/playgroundPrompts";

interface PromptModelSelectProps {
  value: PLAYGROUND_MODEL_TYPE | "";
  onChange: (value: PLAYGROUND_MODEL_TYPE) => void;
  provider: PLAYGROUND_PROVIDERS_TYPES | "";
}

// ALEX ALIGN POP UPS
// ALEX MAKE H-10 a variable
// ALEX BREAK DOWN THE COMPONENT
const PromptModelSelect = ({
  value,
  onChange,
  provider,
}: PromptModelSelectProps) => {
  const inputRef = useRef<HTMLInputElement>(null);
  const [filterValue, setFilterValue] = useState("");

  const handleSelectOpenChange = useCallback((open: boolean) => {
    if (!open) {
      setFilterValue("");
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
    {
      /*flex size-full items-center comet-body-s h-10 w-full hover:bg-primary-foreground pl-2 p-0 rounded-sm*/
    }

    return (
      <div>
        {groupOptions.map((group) => (
          <React.Fragment key={group.label}>
            <DropdownMenuTrigger asChild>
              <div
                key={group.label}
                className="comet-body-s flex h-10 w-full items-center rounded-sm p-0 pl-2 hover:bg-primary-foreground"
              >
                {<group.icon className="comet-body mr-1" />}
                {group.label}
                <ChevronRight className="ml-auto mr-3 size-4 text-light-slate" />
              </div>
            </DropdownMenuTrigger>

            <DropdownMenuContent side="left" hideWhenDetached>
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
            </DropdownMenuContent>
          </React.Fragment>
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
    <DropdownMenu>
      <Select
        value={value || ""}
        onValueChange={handleOnChange}
        onOpenChange={handleSelectOpenChange}
      >
        <SelectTrigger className="size-full">
          <SelectValue
            placeholder="Select a LLM model"
            data-testid="select-a-llm-model"
          >
            <div className="flex items-center gap-2">
              {renderProviderValueIcon()}
              {value}
            </div>
          </SelectValue>
        </SelectTrigger>
        <SelectContent onKeyDown={handleKeyDown}>
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
    </DropdownMenu>
  );
};

export default PromptModelSelect;
