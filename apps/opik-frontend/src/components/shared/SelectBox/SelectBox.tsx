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

export type SelectBoxProps = {
  value: string;
  onChange: (value: string) => void;
  options: DropdownOption<string>[];
  className?: string;
  variant?: "outline" | "ghost";
  placeholder?: string;
  disabled?: boolean;
  testId?: string;
};

export const SelectBox = ({
  value = "",
  onChange,
  options,
  className,
  variant = "outline",
  placeholder = "Select value",
  disabled = false,
  testId,
}: SelectBoxProps) => {
  const variantClass =
    variant === "ghost" ? "border-0 focus:ring-0 h-9 bg-transparent" : "";

  return (
    <Select value={value} onValueChange={onChange} disabled={disabled}>
      <SelectTrigger className={cn(variantClass, className)}>
        <SelectValue placeholder={placeholder} data-testid={testId} />
      </SelectTrigger>
      <SelectContent>
        {options.map((option) => (
          <SelectItem key={option.value} value={option.value}>
            {option.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
};

export default SelectBox;
