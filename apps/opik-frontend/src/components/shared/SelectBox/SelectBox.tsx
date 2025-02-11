import React from "react";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { cn } from "@/lib/utils";
import { DropdownOption } from "@/types/shared";
import isFunction from "lodash/isFunction";

export type SelectBoxProps = {
  value: string;
  onChange: (value: string) => void;
  options: DropdownOption<string>[];
  className?: string;
  variant?: "outline" | "ghost";
  placeholder?: string;
  disabled?: boolean;
  testId?: string;
  renderOption?: (option: DropdownOption<string>) => React.ReactNode;
  renderTrigger?: (value: string) => React.ReactNode;
};

export const SelectBox = ({
  value = "",
  onChange,
  options,
  className,
  variant = "outline",
  placeholder = "Select value",
  disabled = false,
  renderOption,
  renderTrigger,
  testId,
}: SelectBoxProps) => {
  const variantClass =
    variant === "ghost" ? "border-0 focus:ring-0 h-9 bg-transparent" : "";

  return (
    <Select value={value} onValueChange={onChange} disabled={disabled}>
      <SelectTrigger
        className={cn(
          variantClass,
          "data-[placeholder]:text-light-slate",
          className,
        )}
      >
        {isFunction(renderTrigger) ? (
          renderTrigger(value)
        ) : (
          <SelectValue placeholder={placeholder} data-testid={testId} />
        )}
      </SelectTrigger>
      <SelectContent>
        {options.map((option) => {
          if (isFunction(renderOption)) {
            return renderOption(option);
          }

          return (
            <SelectItem
              key={option.value}
              value={option.value}
              description={option.description}
              disabled={option.disabled}
            >
              {option.label}
            </SelectItem>
          );
        })}

        {!options.length && (
          <div className="comet-boby-s p-2 text-light-slate">No items</div>
        )}
      </SelectContent>
    </Select>
  );
};

export default SelectBox;
