import React, { useMemo, useState, useCallback } from "react";
import { ChevronDown, Pencil, Plus } from "lucide-react";
import toLower from "lodash/toLower";

import { Button } from "@/ui/button";
import { Tag } from "@/ui/tag";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { ListAction } from "@/ui/list-action";
import { Separator } from "@/ui/separator";
import { Checkbox } from "@/ui/checkbox";
import { cn, getSelectAllCheckedState } from "@/lib/utils";
import { EvaluatorsRule, UI_EVALUATORS_RULE_TYPE } from "@/types/automations";
import SearchInput from "@/shared/SearchInput/SearchInput";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import DropdownEmptyState from "@/v2/pages-shared/DropdownEmptyState/DropdownEmptyState";
import AddEditRuleDialog from "@/v2/pages-shared/automations/AddEditRuleDialog/AddEditRuleDialog";
import { RULE_TYPE_OPTIONS } from "@/v2/pages-shared/automations/CreateRuleMenu";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import {
  isAlwaysRunRule,
  isTraceRule,
  toggleAllMetrics,
  toggleMetricSelection,
} from "@/v2/pages/PlaygroundPage/metricSelection";
import { usePermissions } from "@/contexts/PermissionsContext";
import emptyMetricsLightUrl from "/images/empty-metrics-light.svg";
import emptyMetricsDarkUrl from "/images/empty-metrics-dark.svg";

interface MetricSelectorProps {
  rules: EvaluatorsRule[];
  selectedRuleIds: string[] | null;
  onSelectionChange: (ruleIds: string[] | null) => void;
  projectId?: string;
  canUsePlayground: boolean;
  datasetColumnNames?: string[];
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onRuleCreated?: (rule: EvaluatorsRule) => void;
}

const MetricSelector: React.FC<MetricSelectorProps> = ({
  rules: allRules,
  selectedRuleIds,
  onSelectionChange,
  projectId,
  canUsePlayground,
  datasetColumnNames,
  open,
  onOpenChange,
  onRuleCreated,
}) => {
  const [search, setSearch] = useState("");
  const [isRuleDialogOpen, setIsRuleDialogOpen] = useState(false);
  const [ruleToEdit, setRuleToEdit] = useState<EvaluatorsRule | null>(null);

  const {
    permissions: { canUpdateOnlineEvaluationRules },
  } = usePermissions();

  const canCreateRule =
    canUsePlayground && canUpdateOnlineEvaluationRules && Boolean(projectId);

  // Thread and span rules cannot score a playground run
  const rules = useMemo(() => allRules.filter(isTraceRule), [allRules]);

  const selectedRuleIdsSet = useMemo(
    () => new Set(selectedRuleIds ?? []),
    [selectedRuleIds],
  );

  // Enabled rules that target experiments score every run regardless of the pick, so they show
  // as checked and cannot be unchecked. The pick list itself only holds the user's own choices.
  const alwaysRunIds = useMemo(
    () => new Set(rules.filter(isAlwaysRunRule).map((rule) => rule.id)),
    [rules],
  );

  const toggleableRules = useMemo(
    () => rules.filter((rule) => !alwaysRunIds.has(rule.id)),
    [rules, alwaysRunIds],
  );

  const isSelected = useCallback(
    (ruleId: string) =>
      selectedRuleIdsSet.has(ruleId) || alwaysRunIds.has(ruleId),
    [selectedRuleIdsSet, alwaysRunIds],
  );

  const selectedCount = rules.filter((rule) => isSelected(rule.id)).length;
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
      if (alwaysRunIds.has(ruleId)) return;
      onSelectionChange(toggleMetricSelection(selectedRuleIds, ruleId));
    },
    [selectedRuleIds, onSelectionChange, alwaysRunIds],
  );

  const handleSelectAll = useCallback(() => {
    onSelectionChange(
      toggleAllMetrics(
        isAllSelected,
        toggleableRules.map((r) => r.id),
      ),
    );
  }, [onSelectionChange, isAllSelected, toggleableRules]);

  const openChangeHandler = useCallback(
    (newOpen: boolean) => {
      onOpenChange(newOpen);
      if (!newOpen) setSearch("");
    },
    [onOpenChange],
  );

  const isCodeMetricEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.PYTHON_EVALUATOR_ENABLED,
  );
  const [createUIType, setCreateUIType] = useState<UI_EVALUATORS_RULE_TYPE>(
    UI_EVALUATORS_RULE_TYPE.llm_judge,
  );
  const openCreateDialog = useCallback(
    (uiType: UI_EVALUATORS_RULE_TYPE = UI_EVALUATORS_RULE_TYPE.llm_judge) => {
      setCreateUIType(uiType);
      setRuleToEdit(null);
      openChangeHandler(false);
      setIsRuleDialogOpen(true);
    },
    [openChangeHandler],
  );
  const openCreateLLMJudgeDialog = useCallback(
    () => openCreateDialog(UI_EVALUATORS_RULE_TYPE.llm_judge),
    [openCreateDialog],
  );

  const openEditDialog = useCallback(
    (rule: EvaluatorsRule) => {
      setRuleToEdit(rule);
      openChangeHandler(false);
      setIsRuleDialogOpen(true);
    },
    [openChangeHandler],
  );

  const handleDialogOpenChange = useCallback((newOpen: boolean) => {
    setIsRuleDialogOpen(newOpen);
    if (!newOpen) setRuleToEdit(null);
  }, []);

  const hasNoRules = rules.length === 0;

  const triggerContent =
    selectedCount === 0 ? (
      <span className="min-w-0 truncate">Select metrics</span>
    ) : (
      <span className="flex min-w-0 items-center gap-1.5">
        <span className="truncate">Metrics</span>
        <Tag
          variant="green"
          size="sm"
          className="group-hover:bg-primary-100 group-hover:text-primary-hover"
        >
          {selectedCount}
        </Tag>
      </span>
    );

  return (
    <>
      <Popover onOpenChange={openChangeHandler} open={open}>
        <PopoverTrigger asChild>
          <div
            tabIndex={0}
            className={cn(
              "flex h-full w-[120px] cursor-pointer items-center gap-1 px-2 text-xs focus:outline-none",
              open
                ? "text-foreground"
                : selectedCount > 0
                  ? "group text-foreground hover:text-primary"
                  : "text-light-slate hover:text-foreground",
            )}
          >
            <div className="flex min-w-0 flex-1 items-center overflow-hidden">
              {triggerContent}
            </div>
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
                onCreate={canCreateRule ? openCreateLLMJudgeDialog : undefined}
              />
            ) : filteredRules.length > 0 ? (
              filteredRules.map((rule) => (
                <div
                  key={rule.id}
                  className={cn(
                    "group flex h-8 items-center gap-2 rounded-md px-3",
                    alwaysRunIds.has(rule.id)
                      ? "cursor-default"
                      : "cursor-pointer hover:bg-primary-foreground",
                  )}
                  onClick={() => handleSelect(rule.id)}
                >
                  {alwaysRunIds.has(rule.id) ? (
                    <TooltipWrapper content="Always runs on experiment traces">
                      <span className="flex shrink-0">
                        <Checkbox checked disabled className="shrink-0" />
                      </span>
                    </TooltipWrapper>
                  ) : (
                    <Checkbox
                      checked={isSelected(rule.id)}
                      className="shrink-0"
                    />
                  )}
                  <TooltipWrapper content={rule.name}>
                    <div className="min-w-0 flex-1">
                      <div className="comet-body-s truncate">{rule.name}</div>
                    </div>
                  </TooltipWrapper>
                  {canUpdateOnlineEvaluationRules && (
                    <TooltipWrapper content="Edit metric">
                      <Button
                        type="button"
                        variant="minimal"
                        size="icon-xs"
                        className="shrink-0 opacity-0 transition-opacity group-hover:opacity-100"
                        onClick={(e) => {
                          e.stopPropagation();
                          openEditDialog(rule);
                        }}
                      >
                        <Pencil className="size-3.5 shrink-0" />
                      </Button>
                    </TooltipWrapper>
                  )}
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
              {filteredRules.length > 0 && toggleableRules.length > 0 && (
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
                      <div className="comet-body-s truncate tabular-nums">
                        {selectedCount} of {rules.length} selected
                      </div>
                    </div>
                  </div>
                </>
              )}
              {canCreateRule && (
                <>
                  <Separator className="my-1" />
                  {RULE_TYPE_OPTIONS.filter(
                    (option) =>
                      isCodeMetricEnabled ||
                      option.value === UI_EVALUATORS_RULE_TYPE.llm_judge,
                  ).map((option) => (
                    <ListAction
                      key={option.value}
                      variant="default"
                      size="sm"
                      onClick={() => openCreateDialog(option.value)}
                    >
                      <Plus className="size-3.5 shrink-0" />
                      New {option.label} metric
                    </ListAction>
                  ))}
                </>
              )}
            </div>
          )}
        </PopoverContent>
      </Popover>

      <AddEditRuleDialog
        key={ruleToEdit ? `edit-${ruleToEdit.id}` : "create"}
        open={isRuleDialogOpen}
        setOpen={handleDialogOpenChange}
        projectId={projectId || ""}
        rule={ruleToEdit ?? undefined}
        mode={ruleToEdit ? "edit" : "create"}
        uiType={createUIType}
        datasetColumnNames={datasetColumnNames}
        hideScopeSelector
        onRuleCreated={onRuleCreated}
      />
    </>
  );
};

export default MetricSelector;
