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
  width?: string;
  variant?: "outline" | "ghost";
};

export const SelectBox = ({
  value = "",
  onChange,
  options,
  width = "180px",
  variant = "outline",
}: SelectBoxProps) => {
  const widthClass = `w-[${width}]`;
  const variantClass = variant === "ghost" ? "border-0 focus:ring-0 h-9" : "";

  return (
    <Select value={value} onValueChange={onChange}>
      <SelectTrigger className={cn(widthClass, variantClass)}>
        <SelectValue placeholder="Select a value" />
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
