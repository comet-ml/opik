import React, { useMemo } from "react";
import { Check, ChevronsUpDown } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { cn } from "@/lib/utils";
import { EvaluatorsRule } from "@/types/automations";
import { Tag } from "@/components/ui/tag";

interface MetricSelectorProps {
  rules: EvaluatorsRule[];
  selectedRuleIds: string[] | null;
  onSelectionChange: (ruleIds: string[] | null) => void;
}

const MetricSelector: React.FC<MetricSelectorProps> = ({
  rules,
  selectedRuleIds,
  onSelectionChange,
}) => {
  const [open, setOpen] = React.useState(false);

  const selectedRules = useMemo(() => {
    if (!selectedRuleIds) return rules;
    return rules.filter((rule) => selectedRuleIds.includes(rule.id));
  }, [rules, selectedRuleIds]);

  const toggleRule = (ruleId: string) => {
    if (!selectedRuleIds) {
      // If all were selected, now deselect all except this one
      onSelectionChange([ruleId]);
    } else {
      const isSelected = selectedRuleIds.includes(ruleId);
      if (isSelected) {
        const newSelection = selectedRuleIds.filter((id) => id !== ruleId);
        // If deselecting last rule, select all
        if (newSelection.length === 0) {
          onSelectionChange(null);
        } else {
          onSelectionChange(newSelection);
        }
      } else {
        const newSelection = [...selectedRuleIds, ruleId];
        // If all are now selected, set to null (all)
        if (newSelection.length === rules.length) {
          onSelectionChange(null);
        } else {
          onSelectionChange(newSelection);
        }
      }
    }
  };

  const selectAll = () => {
    onSelectionChange(null);
  };

  const deselectAll = () => {
    if (rules.length > 0) {
      onSelectionChange([rules[0].id]);
    }
  };

  const isAllSelected = selectedRuleIds === null;
  const displayText = isAllSelected
    ? `All metrics (${rules.length})`
    : `${selectedRules.length} of ${rules.length} metrics`;

  if (rules.length === 0) {
    return null;
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          variant="outline"
          role="combobox"
          aria-expanded={open}
          className="w-[280px] justify-between"
        >
          <span className="truncate">{displayText}</span>
          <ChevronsUpDown className="ml-2 h-4 w-4 shrink-0 opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-[280px] p-0">
        <Command>
          <CommandInput placeholder="Search metrics..." />
          <CommandList>
            <CommandEmpty>No metrics found.</CommandEmpty>
            <CommandGroup>
              <CommandItem onSelect={selectAll} className="cursor-pointer">
                <Check
                  className={cn(
                    "mr-2 h-4 w-4",
                    isAllSelected ? "opacity-100" : "opacity-0",
                  )}
                />
                <span className="font-medium">Select All</span>
                <Tag variant="primary" size="sm" className="ml-auto">
                  {rules.length}
                </Tag>
              </CommandItem>
            </CommandGroup>
            <CommandGroup heading="Metrics">
              {rules.map((rule) => {
                const isSelected =
                  isAllSelected || selectedRuleIds?.includes(rule.id);
                return (
                  <CommandItem
                    key={rule.id}
                    value={rule.name}
                    onSelect={() => toggleRule(rule.id)}
                    className="cursor-pointer"
                  >
                    <Check
                      className={cn(
                        "mr-2 h-4 w-4",
                        isSelected ? "opacity-100" : "opacity-0",
                      )}
                    />
                    <span className="truncate">{rule.name}</span>
                  </CommandItem>
                );
              })}
            </CommandGroup>
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
};

export default MetricSelector;

