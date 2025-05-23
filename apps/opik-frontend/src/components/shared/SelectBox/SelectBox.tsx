import React from "react";
import isFunction from "lodash/isFunction";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";
import { DropdownOption } from "@/types/shared";

export type SelectBoxProps<DataType> = {
  value: string;
  onChange: (value: DataType) => void;
  options: DropdownOption<DataType>[];
  className?: string;
  variant?: "outline" | "ghost";
  placeholder?: string;
  disabled?: boolean;
  testId?: string;
  renderOption?: (option: DropdownOption<DataType>) => React.ReactNode;
  renderTrigger?: (value: string) => React.ReactNode;
};

export const SelectBox = <ValueType extends string>({
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
}: SelectBoxProps<ValueType>) => {
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

          const item = (
            <SelectItem
              key={option.value}
              value={option.value}
              description={option.description}
              disabled={option.disabled}
            >
              {option.label}
            </SelectItem>
          );

          if (option.tooltip) {
            return (
              <TooltipWrapper
                key={`tooltip-${option.value}`}
                content={option.tooltip}
              >
                <div>{item}</div>
              </TooltipWrapper>
            );
          }

          return item;
        })}

        {!options.length && (
          <div className="comet-boby-s p-2 text-light-slate">No items</div>
        )}
      </SelectContent>
    </Select>
  );
};

export default SelectBox;
