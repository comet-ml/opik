import React from "react";
import { RotateCcw, Settings2 } from "lucide-react";

import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { cn } from "@/lib/utils";
import { useClampedIntegerInput } from "@/hooks/useClampedIntegerInput";
import AssertionsField from "@/shared/AssertionField/AssertionsField";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { MAX_RUNS_PER_ITEM } from "@/types/test-suites";
import {
  PASS_CRITERIA_TITLE,
  PASS_CRITERIA_DESCRIPTION,
} from "@/constants/test-suites";

export interface EvaluationCriteriaSectionProps {
  suiteAssertions: string[];
  editableAssertions: string[];
  onChangeAssertion: (index: number, value: string) => void;
  onRemoveAssertion: (index: number) => void;
  onAddAssertion: () => void;
  runsPerItem: number;
  passThreshold: number;
  onRunsPerItemChange: (value: number) => void;
  onPassThresholdChange: (value: number) => void;
  useGlobalPolicy: boolean;
  onRevertToDefaults: () => void;
  onOpenSettings?: () => void;
  defaultRunsPerItem: number;
  defaultPassThreshold: number;
}

const EvaluationCriteriaSection: React.FunctionComponent<
  EvaluationCriteriaSectionProps
> = ({
  suiteAssertions,
  editableAssertions,
  onChangeAssertion,
  onRemoveAssertion,
  onAddAssertion,
  runsPerItem,
  passThreshold,
  onRunsPerItemChange,
  onPassThresholdChange,
  useGlobalPolicy,
  onRevertToDefaults,
  onOpenSettings,
  defaultRunsPerItem,
  defaultPassThreshold,
}) => {
  const runsInput = useClampedIntegerInput({
    value: runsPerItem,
    min: 1,
    max: MAX_RUNS_PER_ITEM,
    onCommit: (v) => {
      onRunsPerItemChange(v);
      if (passThreshold > v) onPassThresholdChange(v);
    },
  });

  const thresholdInput = useClampedIntegerInput({
    value: passThreshold,
    min: 1,
    max: runsPerItem,
    onCommit: onPassThresholdChange,
  });

  const manageSettingsButton = onOpenSettings ? (
    <button
      type="button"
      className="comet-body-xs inline-flex shrink-0 items-center gap-1 text-foreground underline"
      onClick={onOpenSettings}
    >
      <Settings2 className="size-3.5 shrink-0" />
      Manage global assertions
    </button>
  ) : undefined;

  return (
    <div>
      <AssertionsField
        variant="item"
        footerContent={manageSettingsButton}
        readOnlyAssertions={suiteAssertions}
        editableAssertions={editableAssertions}
        onChangeEditable={onChangeAssertion}
        onRemoveEditable={onRemoveAssertion}
        onAdd={onAddAssertion}
      />

      <h3 className="comet-body-s-accented mb-1 mt-6">{PASS_CRITERIA_TITLE}</h3>
      <p className="comet-body-xs mb-4 text-light-slate">
        {PASS_CRITERIA_DESCRIPTION}
      </p>

      <div className="flex items-start overflow-hidden rounded-md border">
        <div className="flex flex-1 gap-4 p-3">
          <div className="flex flex-1 flex-col gap-1">
            <Label className="comet-body-xs-accented">Runs for this item</Label>
            <Input
              dimension="sm"
              className={cn("[&::-webkit-inner-spin-button]:appearance-none", {
                "border-destructive": runsInput.isInvalid,
              })}
              type="number"
              min={1}
              max={MAX_RUNS_PER_ITEM}
              value={runsInput.displayValue}
              onChange={runsInput.onChange}
              onFocus={runsInput.onFocus}
              onBlur={runsInput.onBlur}
              onKeyDown={runsInput.onKeyDown}
            />
            <span className="comet-body-xs text-light-slate">
              Sets how many times this item runs.
            </span>
          </div>
          <div className="flex flex-1 flex-col gap-1">
            <Label className="comet-body-xs-accented">Pass threshold</Label>
            <Input
              dimension="sm"
              className={cn("[&::-webkit-inner-spin-button]:appearance-none", {
                "border-destructive": thresholdInput.isInvalid,
              })}
              type="number"
              min={1}
              max={runsPerItem}
              value={thresholdInput.displayValue}
              onChange={thresholdInput.onChange}
              onFocus={thresholdInput.onFocus}
              onBlur={thresholdInput.onBlur}
              onKeyDown={thresholdInput.onKeyDown}
            />
            <span className="comet-body-xs text-light-slate">
              Define how many runs must succeed.
            </span>
          </div>
        </div>
        <TooltipWrapper
          content={
            useGlobalPolicy
              ? "Already using the suite's defaults"
              : `Revert to suite defaults (runs: ${defaultRunsPerItem}, threshold: ${defaultPassThreshold})`
          }
        >
          <button
            type="button"
            className={cn(
              "flex items-center justify-center self-stretch border-l px-2",
              useGlobalPolicy && "cursor-default opacity-50",
            )}
            onClick={useGlobalPolicy ? undefined : onRevertToDefaults}
            aria-disabled={useGlobalPolicy}
          >
            <RotateCcw className="size-3.5 text-muted-slate" />
          </button>
        </TooltipWrapper>
      </div>
    </div>
  );
};

export default EvaluationCriteriaSection;
