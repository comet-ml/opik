import React from "react";
import { ChevronDown } from "lucide-react";
import * as SelectPrimitive from "@radix-ui/react-select";
import { SelectContent, SelectItem } from "@/ui/select";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { DropdownOption } from "@/types/shared";
import { cn } from "@/lib/utils";

type CodeBlockModeSelectProps = {
  value: string;
  options: DropdownOption<string>[];
  onChange: (value: string) => void;
};

const CodeBlockModeSelect: React.FC<CodeBlockModeSelectProps> = ({
  value,
  options,
  onChange,
}) => {
  const selected = options.find((o) => o.value === value);

  return (
    <SelectPrimitive.Root value={value} onValueChange={onChange}>
      <SelectPrimitive.Trigger
        className={cn(
          "group flex h-6 items-center gap-1 rounded px-2 py-0.5",
          "comet-body-xs text-muted-slate transition-colors hover:bg-muted/50",
          "focus:outline-none focus-visible:ring-2 focus-visible:ring-primary",
        )}
      >
        <span className="truncate">{selected?.label ?? value}</span>
        <ChevronDown className="size-3 shrink-0 text-light-slate transition-transform duration-200 group-data-[state=open]:rotate-180" />
      </SelectPrimitive.Trigger>
      <SelectContent>
        {options.map((option) => {
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
      </SelectContent>
    </SelectPrimitive.Root>
  );
};

export default CodeBlockModeSelect;
