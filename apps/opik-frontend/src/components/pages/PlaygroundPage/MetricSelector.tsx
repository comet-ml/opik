import React, {
  useMemo,
  useState,
  useCallback,
  useEffect,
  useRef,
} from "react";
import { ChevronDown, ExternalLink, Plus } from "lucide-react";
import { Link } from "@tanstack/react-router";

import { Button } from "@/components/ui/button";
import RemovableTag from "@/components/shared/RemovableTag/RemovableTag";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { ListAction } from "@/components/ui/list-action";
import { Separator } from "@/components/ui/separator";
import { Checkbox } from "@/components/ui/checkbox";
import { EvaluatorsRule } from "@/types/automations";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import toLower from "lodash/toLower";
import { cn } from "@/lib/utils";
import { usePermissions } from "@/contexts/PermissionsContext";

const MAX_VISIBLE_TAGS = 3;

interface MetricSelectorProps {
  rules: EvaluatorsRule[];
  selectedRuleIds: string[] | null;
  onSelectionChange: (ruleIds: string[] | null) => void;
  datasetId: string | null;
  onCreateRuleClick: () => void;
  workspaceName: string;
  canUsePlayground: boolean;
}

const MetricSelector: React.FC<MetricSelectorProps> = ({
  rules,
  selectedRuleIds,
  onSelectionChange,
  datasetId,
  onCreateRuleClick,
  workspaceName,
  canUsePlayground,
}) => {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const tagsRef = useRef<HTMLDivElement>(null);
  const deletingRef = useRef(false);

  const {
    permissions: { canUpdateOnlineEvaluationRules },
  } = usePermissions();

  const isAllSelected =
    selectedRuleIds === null || selectedRuleIds.length === rules.length;

  const selectedRules = useMemo(() => {
    if (!selectedRuleIds) return rules;
    return rules.filter((rule) => selectedRuleIds.includes(rule.id));
  }, [rules, selectedRuleIds]);

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
      if (newOpen && !datasetId) return;
      if (deletingRef.current) {
        deletingRef.current = false;
        return;
      }
      setOpen(newOpen);
      if (!newOpen) setSearch("");
    },
    [datasetId],
  );

  const isSelected = useCallback(
    (ruleId: string) => {
      if (isAllSelected) return true;
      return selectedRuleIds?.includes(ruleId) || false;
    },
    [isAllSelected, selectedRuleIds],
  );

  const hasNoRules = rules.length === 0;
  const isDisabled = !datasetId;

  useEffect(() => {
    if (!datasetId && open) {
      setOpen(false);
      setSearch("");
    }
  }, [datasetId, open]);

  useEffect(() => {
    if (open && tagsRef.current) {
      tagsRef.current.scrollTo({
        left: tagsRef.current.scrollWidth,
        behavior: "smooth",
      });
    }
  }, [open, selectedRules.length]);

  const visibleTags = selectedRules.slice(
    0,
    open ? undefined : MAX_VISIBLE_TAGS,
  );
  const overflowCount = open
    ? 0
    : Math.max(0, selectedRules.length - MAX_VISIBLE_TAGS);

  const renderTriggerContent = () => {
    if (!datasetId || rules.length === 0 || selectedRules.length === 0) {
      return <span className="truncate text-muted-slate">Select metrics</span>;
    }

    return (
      <div className="flex min-w-0 flex-1 items-center gap-1 overflow-hidden">
        <div
          ref={tagsRef}
          className={cn(
            "comet-no-scrollbar flex min-w-0 shrink items-center gap-2",
            open ? "overflow-x-auto" : "overflow-hidden",
          )}
        >
          {visibleTags.map((rule) => (
            <RemovableTag
              key={rule.id}
              label={rule.name}
              variant="purple"
              size="default"
              onDelete={() => {
                deletingRef.current = true;
                handleSelect(rule.id);
              }}
            />
          ))}
        </div>
        {overflowCount > 0 && (
          <span className="ml-1 shrink-0 text-xs text-muted-slate">
            +{overflowCount}
          </span>
        )}
      </div>
    );
  };

  const triggerElement = (
    <div
      tabIndex={0}
      className={cn(
        "flex h-8 w-full cursor-pointer items-center gap-1 rounded-md border border-input bg-background px-3 text-sm hover:shadow-sm focus:border-primary focus:outline-none",
        isDisabled && "cursor-not-allowed opacity-50",
        open && "border-primary",
      )}
    >
      {renderTriggerContent()}
      <ChevronDown
        className={cn(
          "ml-auto size-4 shrink-0",
          isDisabled ? "text-muted-gray" : "text-light-slate",
        )}
      />
    </div>
  );

  return (
    <Popover onOpenChange={openChangeHandler} open={open && !isDisabled} modal>
      <PopoverTrigger asChild={!isDisabled} disabled={isDisabled}>
        {isDisabled ? (
          <TooltipWrapper content="Select a dataset first to choose metrics">
            <span className="block">{triggerElement}</span>
          </TooltipWrapper>
        ) : (
          triggerElement
        )}
      </PopoverTrigger>
      <PopoverContent
        align="start"
        className="relative w-[--radix-popover-trigger-width] p-1 pt-12"
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
              {canUsePlayground && canUpdateOnlineEvaluationRules && (
                <div className="comet-body-s text-muted-slate">
                  Create an online evaluation rule for the Playground project to
                  generate metrics for your outputs.
                </div>
              )}
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
                          to={`/${workspaceName}/online-evaluation?${
                            canUpdateOnlineEvaluationRules
                              ? `editRule=${rule.id}&`
                              : ""
                          }search=${rule.id}&filters=[]`}
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
          {canUsePlayground && canUpdateOnlineEvaluationRules && (
            <>
              <Separator className="my-1" />
              <ListAction
                onClick={() => {
                  setOpen(false);
                  onCreateRuleClick();
                }}
              >
                <Plus className="size-3.5 shrink-0" />
                Add new
              </ListAction>
            </>
          )}
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default MetricSelector;
