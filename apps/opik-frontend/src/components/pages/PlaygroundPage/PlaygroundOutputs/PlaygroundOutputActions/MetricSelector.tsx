import React, { useMemo, useState, useCallback } from "react";
import { ChevronDown } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Separator } from "@/components/ui/separator";
import { Checkbox } from "@/components/ui/checkbox";
import { EvaluatorsRule } from "@/types/automations";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import toLower from "lodash/toLower";

interface MetricSelectorProps {
  rules: EvaluatorsRule[];
  selectedRuleIds: string[] | null;
  onSelectionChange: (ruleIds: string[] | null) => void;
  datasetId: string | null;
}

const MetricSelector: React.FC<MetricSelectorProps> = ({
  rules,
  selectedRuleIds,
  onSelectionChange,
  datasetId,
}) => {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");

  const isAllSelected =
    selectedRuleIds === null || selectedRuleIds.length === rules.length;

  const selectedRulesCount = useMemo(() => {
    if (selectedRuleIds === null) {
      return rules.length;
    }
    return selectedRuleIds.length;
  }, [selectedRuleIds, rules.length]);

  const filteredRules = useMemo(() => {
    if (!search) return rules;
    const searchLower = toLower(search);
    return rules.filter((rule) => toLower(rule.name).includes(searchLower));
  }, [rules, search]);

  const handleSelect = useCallback(
    (ruleId: string) => {
      if (selectedRuleIds === null || selectedRuleIds.length === rules.length) {
        // If all selected (null) or all specific items selected, deselect this one
        const allRuleIds = rules.map((r) => r.id);
        const newSelection = allRuleIds.filter((id) => id !== ruleId);
        onSelectionChange(newSelection.length > 0 ? newSelection : null);
      } else {
        // Some items selected or none selected
        const isSelected = selectedRuleIds.includes(ruleId);
        if (isSelected) {
          const newSelection = selectedRuleIds.filter((id) => id !== ruleId);
          // If deselecting the last one, select all (null)
          onSelectionChange(newSelection.length > 0 ? newSelection : null);
        } else {
          const newSelection = [...selectedRuleIds, ruleId];
          // If all are now selected, set to null (all)
          onSelectionChange(
            newSelection.length === rules.length ? null : newSelection,
          );
        }
      }
    },
    [selectedRuleIds, rules, onSelectionChange],
  );

  const handleSelectAll = useCallback(() => {
    // Toggle between all selected (null) and none selected ([])
    if (isAllSelected) {
      onSelectionChange([]);
    } else {
      onSelectionChange(null);
    }
  }, [onSelectionChange, isAllSelected]);

  const openChangeHandler = useCallback((newOpen: boolean) => {
    setOpen(newOpen);
    if (!newOpen) {
      setSearch("");
    }
  }, []);

  const displayValue = useMemo(() => {
    if (rules.length === 0) {
      return "No metrics";
    }
    if (isAllSelected) {
      return `All metrics (${rules.length})`;
    }
    return `${selectedRulesCount} of ${rules.length} metrics`;
  }, [isAllSelected, selectedRulesCount, rules.length]);

  const isSelected = useCallback(
    (ruleId: string) => {
      if (isAllSelected) return true;
      return selectedRuleIds?.includes(ruleId) || false;
    },
    [isAllSelected, selectedRuleIds],
  );

  const hasNoRules = rules.length === 0;

  return (
    <Popover onOpenChange={openChangeHandler} open={open} modal>
      <PopoverTrigger asChild>
        <Button
          className="group w-[280px] justify-between"
          size="sm"
          variant="outline"
          type="button"
          disabled={hasNoRules || !datasetId}
        >
          <span className="truncate">{displayValue}</span>
          <ChevronDown className="ml-2 size-4 shrink-0 text-light-slate" />
        </Button>
      </PopoverTrigger>
      <PopoverContent
        align="end"
        style={{ width: "280px" }}
        className="relative p-1 pt-12"
        hideWhenDetached
        onCloseAutoFocus={(e) => e.preventDefault()}
      >
        {!hasNoRules && (
          <div className="absolute inset-x-1 top-0 h-12">
            <SearchInput
              searchText={search}
              setSearchText={setSearch}
              placeholder="Search metrics"
              variant="ghost"
            />
            <Separator className="mt-1" />
          </div>
        )}
        <div className="max-h-[40vh] overflow-y-auto overflow-x-hidden">
          {hasNoRules ? (
            <div className="flex h-20 items-center justify-center text-center text-muted-foreground">
              <div className="px-4">
                <div className="comet-body-s">No metrics configured</div>
                <div className="comet-body-xs mt-1">
                  Configure automation rules for the playground project
                </div>
              </div>
            </div>
          ) : filteredRules.length > 0 ? (
            <>
              {filteredRules.map((rule) => (
                <div
                  key={rule.id}
                  className="flex h-10 cursor-pointer items-center gap-2 rounded-md px-4 hover:bg-primary-foreground"
                  onClick={() => handleSelect(rule.id)}
                >
                  <Checkbox
                    checked={isSelected(rule.id)}
                    className="shrink-0"
                  />
                  <div className="min-w-0 flex-1">
                    <div className="comet-body-s truncate">{rule.name}</div>
                  </div>
                </div>
              ))}
            </>
          ) : (
            <div className="flex h-20 items-center justify-center text-muted-foreground">
              No metrics found
            </div>
          )}
        </div>

        {!hasNoRules && filteredRules.length > 0 && (
          <div className="sticky inset-x-0 bottom-0">
            <Separator className="my-1" />
            <div
              className="flex h-10 cursor-pointer items-center gap-2 rounded-md px-4 hover:bg-primary-foreground"
              onClick={handleSelectAll}
            >
              <Checkbox checked={isAllSelected} className="shrink-0" />
              <div className="min-w-0 flex-1">
                <div className="comet-body-s truncate">Select all</div>
              </div>
            </div>
          </div>
        )}
      </PopoverContent>
    </Popover>
  );
};

export default MetricSelector;
