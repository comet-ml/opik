import React, { useMemo, useState, useCallback } from "react";
import { ChevronDown, ExternalLink, Plus } from "lucide-react";
import { Link } from "@tanstack/react-router";

import { Button } from "@/ui/button";
import { Tag } from "@/ui/tag";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { ListAction } from "@/ui/list-action";
import { Separator } from "@/ui/separator";
import { Checkbox } from "@/ui/checkbox";
import { cn, getSelectAllCheckedState } from "@/lib/utils";
import { EvaluatorsRule } from "@/types/automations";
import SearchInput from "@/shared/SearchInput/SearchInput";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import DropdownEmptyState from "@/v2/pages-shared/DropdownEmptyState/DropdownEmptyState";
import AddEditRuleDialog from "@/v2/pages-shared/automations/AddEditRuleDialog/AddEditRuleDialog";
import {
  toggleAllMetrics,
  toggleMetricSelection,
} from "@/v2/pages/PlaygroundPage/metricSelection";
import toLower from "lodash/toLower";
import { usePermissions } from "@/contexts/PermissionsContext";
import emptyMetricsLightUrl from "/images/empty-metrics-light.svg";
import emptyMetricsDarkUrl from "/images/empty-metrics-dark.svg";

interface MetricSelectorProps {
  rules: EvaluatorsRule[];
  selectedRuleIds: string[] | null;
  onSelectionChange: (ruleIds: string[] | null) => void;
  workspaceName: string;
  projectId?: string;
  canUsePlayground: boolean;
}

const MetricSelector: React.FC<MetricSelectorProps> = ({
  rules,
  selectedRuleIds,
  onSelectionChange,
  workspaceName,
  projectId,
  canUsePlayground,
}) => {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [isRuleDialogOpen, setIsRuleDialogOpen] = useState(false);

  const {
    permissions: { canUpdateOnlineEvaluationRules },
  } = usePermissions();

  const canCreateRule =
    canUsePlayground && canUpdateOnlineEvaluationRules && Boolean(projectId);

  const selectedRuleIdsSet = useMemo(
    () => new Set(selectedRuleIds ?? []),
    [selectedRuleIds],
  );

  const selectedRules = useMemo(() => {
    if (!selectedRuleIds) return rules;
    return rules.filter((rule) => selectedRuleIdsSet.has(rule.id));
  }, [rules, selectedRuleIds, selectedRuleIdsSet]);

  const selectedCount = selectedRules.length;
  const isAllSelected = rules.length > 0 && selectedCount === rules.length;
  const selectAllCheckedState = getSelectAllCheckedState(
    selectedCount,
    rules.length,
  );

  const filteredRules = useMemo(() => {
    if (!search) return rules;
    const searchLower = toLower(search);
    return rules.filter((rule) => toLower(rule.name).includes(searchLower));
  }, [rules, search]);

  const handleSelect = useCallback(
    (ruleId: string) => {
      onSelectionChange(
        toggleMetricSelection(
          selectedRuleIds,
          ruleId,
          rules.map((r) => r.id),
        ),
      );
    },
    [selectedRuleIds, rules, onSelectionChange],
  );

  const handleSelectAll = useCallback(() => {
    onSelectionChange(toggleAllMetrics(isAllSelected));
  }, [onSelectionChange, isAllSelected]);

  const openChangeHandler = useCallback((newOpen: boolean) => {
    setOpen(newOpen);
    if (!newOpen) setSearch("");
  }, []);

  const isSelected = useCallback(
    (ruleId: string) => {
      if (isAllSelected) return true;
      return selectedRuleIdsSet.has(ruleId);
    },
    [isAllSelected, selectedRuleIdsSet],
  );

  const hasNoRules = rules.length === 0;

  const triggerContent =
    selectedCount === 0 ? (
      <span className="truncate">Select metrics</span>
    ) : (
      <div className="flex items-center gap-1.5">
        <span>Metrics</span>
        <Tag
          variant="green"
          size="sm"
          className="group-hover:bg-primary-100 group-hover:text-primary-hover"
        >
          {selectedCount}
        </Tag>
      </div>
    );

  return (
    <>
      <Popover onOpenChange={openChangeHandler} open={open} modal>
        <PopoverTrigger asChild>
          <div
            tabIndex={0}
            className={cn(
              "flex h-full cursor-pointer items-center gap-1 px-2 text-xs focus:outline-none",
              open
                ? "text-foreground"
                : selectedCount > 0
                  ? "group text-foreground hover:text-primary"
                  : "text-light-slate hover:text-foreground",
            )}
          >
            {triggerContent}
            <ChevronDown
              className={cn(
                "size-3.5 shrink-0 text-light-slate transition-transform group-hover:text-primary",
                open && "rotate-180",
              )}
            />
          </div>
        </PopoverTrigger>
        <PopoverContent
          align="start"
          sideOffset={6}
          className="flex max-h-[260px] w-[210px] flex-col p-1"
          hideWhenDetached
          onCloseAutoFocus={(e) => e.preventDefault()}
        >
          {!hasNoRules && (
            <>
              <SearchInput
                searchText={search}
                setSearchText={setSearch}
                variant="ghost"
                dimension="sm"
                disableDebounce
                className="shrink-0"
              />
              <Separator className="my-1 shrink-0" />
            </>
          )}
          <div className="min-h-0 flex-1 overflow-y-auto overflow-x-hidden">
            {hasNoRules ? (
              <DropdownEmptyState
                lightImageUrl={emptyMetricsLightUrl}
                darkImageUrl={emptyMetricsDarkUrl}
                title="No metrics yet"
                ctaLabel={canCreateRule ? "Create metric" : undefined}
                onCreate={
                  canCreateRule
                    ? () => {
                        setOpen(false);
                        setIsRuleDialogOpen(true);
                      }
                    : undefined
                }
              />
            ) : filteredRules.length > 0 ? (
              filteredRules.map((rule) => (
                <div
                  key={rule.id}
                  className="group flex h-8 cursor-pointer items-center gap-2 rounded-md px-3 hover:bg-primary-foreground"
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
                  <TooltipWrapper content="Open in a new tab">
                    <Button
                      type="button"
                      variant="minimal"
                      size="icon-xs"
                      asChild
                    >
                      <Link
                        to={`/${workspaceName}/projects/${projectId}/online-evaluation${
                          canUpdateOnlineEvaluationRules
                            ? `?editRule=${rule.id}`
                            : ""
                        }`}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="inline-flex shrink-0 items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100"
                        onClick={(e) => e.stopPropagation()}
                      >
                        <ExternalLink className="size-3.5 shrink-0" />
                      </Link>
                    </Button>
                  </TooltipWrapper>
                </div>
              ))
            ) : (
              <div className="comet-body-s flex h-20 items-center justify-center text-muted-slate">
                No metrics found
              </div>
            )}
          </div>

          {!hasNoRules && (
            <div className="shrink-0">
              {filteredRules.length > 0 && (
                <>
                  <Separator className="my-1" />
                  <div
                    className="flex h-8 cursor-pointer items-center gap-2 rounded-md px-3 hover:bg-primary-foreground"
                    onClick={handleSelectAll}
                  >
                    <Checkbox
                      checked={selectAllCheckedState}
                      className="shrink-0"
                      tabIndex={-1}
                    />
                    <div className="min-w-0 flex-1">
                      <div className="comet-body-s truncate">
                        {selectedCount} of {rules.length} selected
                      </div>
                    </div>
                  </div>
                </>
              )}
              {canCreateRule && (
                <>
                  <Separator className="my-1" />
                  <ListAction
                    variant="default"
                    size="sm"
                    onClick={() => {
                      setOpen(false);
                      setIsRuleDialogOpen(true);
                    }}
                  >
                    <Plus className="size-3.5 shrink-0" />
                    New metric
                  </ListAction>
                </>
              )}
            </div>
          )}
        </PopoverContent>
      </Popover>

      <AddEditRuleDialog
        open={isRuleDialogOpen}
        setOpen={setIsRuleDialogOpen}
        projectId={projectId || ""}
        hideScopeSelector
      />
    </>
  );
};

export default MetricSelector;
