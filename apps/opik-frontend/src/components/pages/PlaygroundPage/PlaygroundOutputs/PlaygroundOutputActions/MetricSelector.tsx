import React, { useMemo, useState, useCallback, useEffect } from "react";
import { ChevronDown, ExternalLink, Plus } from "lucide-react";
import { Link } from "@tanstack/react-router";

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
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import toLower from "lodash/toLower";
import { cn } from "@/lib/utils";

interface MetricSelectorProps {
  rules: EvaluatorsRule[];
  selectedRuleIds: string[] | null;
  onSelectionChange: (ruleIds: string[] | null) => void;
  datasetId: string | null;
  onCreateRuleClick?: () => void;
  workspaceName: string;
}

const MetricSelector: React.FC<MetricSelectorProps> = ({
  rules,
  selectedRuleIds,
  onSelectionChange,
  datasetId,
  onCreateRuleClick,
  workspaceName,
}) => {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");

  const isAllSelected =
    selectedRuleIds === null || selectedRuleIds.length === rules.length;

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
        onSelectionChange(newSelection.length > 0 ? newSelection : []);
      } else {
        // Some items selected or none selected
        const isSelected = selectedRuleIds.includes(ruleId);
        if (isSelected) {
          const newSelection = selectedRuleIds.filter((id) => id !== ruleId);
          // If deselecting the last one, set to empty array (none selected)
          onSelectionChange(newSelection.length > 0 ? newSelection : []);
        } else {
          const newSelection = [...selectedRuleIds, ruleId];
          // If all are now selected, set to null (all selected)
          onSelectionChange(
            newSelection.length === rules.length ? null : newSelection,
          );
        }
      }
    },
    [selectedRuleIds, rules, onSelectionChange],
  );

  const handleSelectAll = useCallback(
    (checked?: boolean | "indeterminate") => {
      // Toggle between all selected (null) and none selected ([])
      if (checked !== undefined && checked !== "indeterminate") {
        // Called from checkbox onCheckedChange - checked is the NEW desired state
        // true = check = select all (null), false = uncheck = deselect all ([])
        onSelectionChange(checked ? null : []);
      } else {
        // Called from div onClick or indeterminate state - toggle based on current state
        const allSelected =
          selectedRuleIds === null ||
          (Array.isArray(selectedRuleIds) &&
            selectedRuleIds.length === rules.length &&
            rules.length > 0);

        // Toggle: if all selected, deselect; if not all selected, select all
        onSelectionChange(allSelected ? [] : null);
      }
    },
    [onSelectionChange, selectedRuleIds, rules.length],
  );

  const openChangeHandler = useCallback(
    (newOpen: boolean) => {
      // Prevent opening if disabled (no dataset selected)
      if (newOpen && !datasetId) {
        return;
      }
      setOpen(newOpen);
      if (!newOpen) {
        setSearch("");
      }
    },
    [datasetId],
  );

  const selectedRules = useMemo(() => {
    if (!selectedRuleIds) {
      return rules;
    }
    return rules.filter((rule) => selectedRuleIds.includes(rule.id));
  }, [rules, selectedRuleIds]);

  const displayValue = useMemo(() => {
    if (!datasetId) {
      return "Select metrics";
    }
    if (rules.length === 0) {
      return "Select metrics";
    }
    if (isAllSelected) {
      return "All selected";
    }
    if (selectedRules.length === 0) {
      return "Select metrics";
    }
    return selectedRules.map((rule) => rule.name).join(", ");
  }, [isAllSelected, selectedRules, rules.length, datasetId]);

  const tooltipContent = useMemo(() => {
    if (!datasetId && rules.length > 0) {
      return "Select a dataset first to choose metrics";
    }
    if (datasetId && selectedRules.length > 0) {
      return selectedRules.map((rule) => rule.name).join(", ");
    }
    return null;
  }, [datasetId, rules.length, selectedRules]);

  const isSelected = useCallback(
    (ruleId: string) => {
      if (isAllSelected) return true;
      return selectedRuleIds?.includes(ruleId) || false;
    },
    [isAllSelected, selectedRuleIds],
  );

  const hasNoRules = rules.length === 0;
  const isDisabled = !datasetId;

  // Close popover when dataset is deselected
  useEffect(() => {
    if (!datasetId && open) {
      setOpen(false);
      setSearch("");
    }
  }, [datasetId, open]);

  const buttonElement = (
    <Button
      className="group w-[200px] justify-between"
      size="sm"
      variant="outline"
      type="button"
      disabled={isDisabled}
    >
      <span className="truncate">{displayValue}</span>
      <ChevronDown
        className={cn(
          "ml-2 size-4 shrink-0",
          isDisabled ? "text-muted-gray" : "text-light-slate",
        )}
      />
    </Button>
  );

  return (
    <Popover onOpenChange={openChangeHandler} open={open && !isDisabled} modal>
      {tooltipContent ? (
        <TooltipWrapper content={tooltipContent}>
          <PopoverTrigger asChild={!isDisabled} disabled={isDisabled}>
            {isDisabled ? (
              <span className="inline-block w-[200px]">{buttonElement}</span>
            ) : (
              buttonElement
            )}
          </PopoverTrigger>
        </TooltipWrapper>
      ) : (
        <PopoverTrigger asChild disabled={isDisabled}>
          {buttonElement}
        </PopoverTrigger>
      )}
      <PopoverContent
        align="end"
        style={{ width: "200px" }}
        className="relative p-1 pt-12"
        hideWhenDetached
        onCloseAutoFocus={(e) => e.preventDefault()}
      >
        <div className="absolute inset-x-1 top-0 h-12">
          <SearchInput
            searchText={search}
            setSearchText={setSearch}
            placeholder="Search"
            variant="ghost"
          />
          <Separator className="mt-1" />
        </div>
        <div className="max-h-[40vh] overflow-y-auto overflow-x-hidden">
          {hasNoRules ? (
            <div className="flex min-h-[120px] flex-col items-center justify-center px-4 py-2 text-center">
              <div className="comet-body-s-accented pb-1 text-foreground">
                No metrics available
              </div>
              <div className="comet-body-s text-muted-slate">
                Create an online evaluation rule for the Playground project to
                generate metrics for your outputs.
              </div>
            </div>
          ) : filteredRules.length > 0 ? (
            <>
              {filteredRules.map((rule) => (
                <div
                  key={rule.id}
                  className="group flex h-10 cursor-pointer items-center gap-2 rounded-md px-4 hover:bg-primary-foreground"
                  onClick={() => handleSelect(rule.id)}
                >
                  <Checkbox
                    checked={isSelected(rule.id)}
                    className="shrink-0"
                  />
                  <TooltipWrapper content={rule.name}>
                    <div className="min-w-0 flex-1">
                      <div className="comet-body-s truncate">{rule.name}</div>
                    </div>
                  </TooltipWrapper>
                  <div className="flex shrink-0 items-center justify-center rounded">
                    <TooltipWrapper content="Open in a new tab">
                      <Button
                        type="button"
                        variant="minimal"
                        size="icon-xs"
                        asChild
                      >
                        <Link
                          to={`/${workspaceName}/online-evaluation?editRule=${rule.id}&search=${rule.id}&filters=[]`}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="inline-flex items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100"
                          onClick={(e) => e.stopPropagation()}
                        >
                          <ExternalLink className="size-3.5 shrink-0" />
                        </Link>
                      </Button>
                    </TooltipWrapper>
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

        <div className="sticky inset-x-0 bottom-0">
          {!hasNoRules && filteredRules.length > 0 && (
            <>
              <Separator className="my-1" />
              <div
                className="flex h-10 cursor-pointer items-center gap-2 rounded-md px-4 hover:bg-primary-foreground"
                onClick={() => handleSelectAll()}
              >
                <Checkbox
                  checked={isAllSelected}
                  className="shrink-0"
                  onCheckedChange={(checked) => handleSelectAll(checked)}
                />
                <div className="min-w-0 flex-1">
                  <div className="comet-body-s truncate">Select all</div>
                </div>
              </div>
            </>
          )}
          {onCreateRuleClick && (
            <>
              <Separator className="my-1" />
              <div
                className="flex h-10 cursor-pointer items-center rounded-md px-4 hover:bg-primary-foreground"
                onClick={() => {
                  setOpen(false);
                  onCreateRuleClick();
                }}
              >
                <div className="comet-body-s flex items-center gap-2 text-primary">
                  <Plus className="size-3.5 shrink-0" />
                  <span>Create a new rule</span>
                </div>
              </div>
            </>
          )}
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default MetricSelector;
