import React from "react";
import { Path, useFieldArray, UseFormReturn } from "react-hook-form";
import { LayoutGrid, Plus, Trash } from "lucide-react";
import get from "lodash/get";

import { FormControl, FormField } from "@/ui/form";
import { Input } from "@/ui/input";
import { Button } from "@/ui/button";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import {
  Tooltip,
  TooltipContent,
  TooltipPortal,
  TooltipTrigger,
} from "@/ui/tooltip";
import SelectBox from "@/shared/SelectBox/SelectBox";
import FeedbackDefinitionsAndScoresSelectBox, {
  ScoreSource,
} from "@/v2/pages-shared/experiments/FeedbackDefinitionsAndScoresSelectBox/FeedbackDefinitionsAndScoresSelectBox";
import {
  AlertFormType,
  FeedbackScoreConditionGroupType,
  FeedbackScoreConditionType,
} from "./schema";
import { ALERT_EVENT_TYPE } from "@/types/alerts";
import { cn } from "@/lib/utils";
import {
  OPERATOR_VALUES,
  WINDOW_LABEL_BY_VALUE,
  WINDOW_OPTIONS,
} from "./constants";

type FeedbackScoreConditionsProps = {
  form: UseFormReturn<AlertFormType>;
  triggerIndex: number;
  eventType: ALERT_EVENT_TYPE;
  projectId: string;
};

export const DEFAULT_FEEDBACK_SCORE_CONDITION: FeedbackScoreConditionType = {
  threshold: "",
  window: "86400",
  name: "",
  operator: ">",
};

export const DEFAULT_FEEDBACK_SCORE_CONDITION_GROUP: FeedbackScoreConditionGroupType =
  {
    conditions: [DEFAULT_FEEDBACK_SCORE_CONDITION],
  };

const CONDITION_FIELDS = ["name", "operator", "threshold", "window"] as const;
type ConditionField = (typeof CONDITION_FIELDS)[number];

// A tooltip wrapper for disabled controls — Radix tooltips don't fire on
// elements with pointer-events: none, so we wrap the disabled child in a
// span that intercepts hover/focus.
const DisabledTooltip: React.FC<{
  message: string;
  disabled: boolean;
  children: React.ReactNode;
}> = ({ message, disabled, children }) => {
  if (!disabled) return <>{children}</>;
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span className="inline-flex cursor-not-allowed">{children}</span>
      </TooltipTrigger>
      <TooltipPortal>
        <TooltipContent side="top">{message}</TooltipContent>
      </TooltipPortal>
    </Tooltip>
  );
};

const SeparatorBadge: React.FC<{ kind: "AND" | "OR" }> = ({ kind }) => (
  <div className="py-0.5">
    <span className="text-xs font-medium leading-4 text-violet-600">
      {kind}
    </span>
  </div>
);

const FeedbackScoreConditions: React.FC<FeedbackScoreConditionsProps> = ({
  form,
  triggerIndex,
  eventType,
  projectId,
}) => {
  const groupsFieldArray = useFieldArray({
    control: form.control,
    name: `triggers.${triggerIndex}.groups` as "triggers.0.groups",
  });

  const scoreSource =
    eventType === ALERT_EVENT_TYPE.trace_thread_feedback_score
      ? ScoreSource.THREADS
      : ScoreSource.TRACES;

  const addGroup = () =>
    groupsFieldArray.append({
      conditions: [{ ...DEFAULT_FEEDBACK_SCORE_CONDITION }],
    });

  const removeGroup = (groupIndex: number) =>
    groupsFieldArray.remove(groupIndex);

  const canDeleteGroup = groupsFieldArray.fields.length > 1;

  return (
    <div className="flex flex-col gap-2">
      {groupsFieldArray.fields.map((group, groupIndex) => (
        <React.Fragment key={group.id}>
          {groupIndex > 0 && <SeparatorBadge kind="OR" />}
          <ConditionGroup
            form={form}
            triggerIndex={triggerIndex}
            groupIndex={groupIndex}
            scoreSource={scoreSource}
            projectId={projectId}
            label={`Group ${groupIndex + 1}`}
            onRemove={() => removeGroup(groupIndex)}
            canRemove={canDeleteGroup}
          />
        </React.Fragment>
      ))}
      <div className="flex h-8 items-center justify-center rounded-md border border-dashed border-border bg-soft-background">
        <Button
          type="button"
          variant="ghost"
          size="xs"
          className="text-foreground hover:text-primary-hover"
          onClick={addGroup}
        >
          <Plus className="mr-0.5 size-3" />
          Add OR group
        </Button>
      </div>
    </div>
  );
};

type ConditionGroupProps = {
  form: UseFormReturn<AlertFormType>;
  triggerIndex: number;
  groupIndex: number;
  scoreSource: ScoreSource;
  projectId: string;
  label: string;
  onRemove: () => void;
  canRemove: boolean;
};

const ConditionGroup: React.FC<ConditionGroupProps> = ({
  form,
  triggerIndex,
  groupIndex,
  scoreSource,
  projectId,
  label,
  onRemove,
  canRemove,
}) => {
  const conditionsFieldArray = useFieldArray({
    control: form.control,
    name: `triggers.${triggerIndex}.groups.${groupIndex}.conditions` as "triggers.0.groups.0.conditions",
  });

  const addCondition = () =>
    conditionsFieldArray.append({ ...DEFAULT_FEEDBACK_SCORE_CONDITION });

  const removeCondition = (conditionIndex: number) =>
    conditionsFieldArray.remove(conditionIndex);

  return (
    <div className="overflow-hidden rounded-md border border-border bg-soft-background">
      <div className="flex h-8 items-center justify-between pl-2 pr-3">
        <div className="flex items-center gap-1.5">
          <span className="flex size-4 items-center justify-center rounded bg-violet-600 text-white">
            <LayoutGrid className="size-2.5" />
          </span>
          <span className="text-xs font-medium leading-4 text-muted-slate">
            {label}
          </span>
        </div>
        <DisabledTooltip
          disabled={!canRemove}
          message="Can't remove the last group — every alert needs at least one."
        >
          <Button
            type="button"
            variant="minimal"
            size="icon-3xs"
            className="size-3 [&>svg]:size-3"
            onClick={onRemove}
            disabled={!canRemove}
            aria-label="Remove group"
          >
            <Trash />
          </Button>
        </DisabledTooltip>
      </div>
      <div className="flex flex-col gap-1.5 px-1.5 pb-1.5">
        {conditionsFieldArray.fields.map((condition, conditionIndex) => (
          <React.Fragment key={condition.id}>
            {conditionIndex > 0 && <SeparatorBadge kind="AND" />}
            <ConditionRow
              form={form}
              triggerIndex={triggerIndex}
              groupIndex={groupIndex}
              conditionIndex={conditionIndex}
              scoreSource={scoreSource}
              projectId={projectId}
              onDelete={() => removeCondition(conditionIndex)}
              canDelete={conditionsFieldArray.fields.length > 1}
            />
          </React.Fragment>
        ))}
        <Button
          type="button"
          variant="ghost"
          size="xs"
          className="self-start pl-1 text-foreground hover:text-primary-hover"
          onClick={addCondition}
        >
          <Plus className="mr-0.5 size-3" />
          Add AND condition
        </Button>
      </div>
    </div>
  );
};

type ConditionRowProps = {
  form: UseFormReturn<AlertFormType>;
  triggerIndex: number;
  groupIndex: number;
  conditionIndex: number;
  scoreSource: ScoreSource;
  projectId: string;
  onDelete: () => void;
  canDelete: boolean;
};

const fieldPath = (
  triggerIndex: number,
  groupIndex: number,
  conditionIndex: number,
  field: ConditionField,
) =>
  `triggers.${triggerIndex}.groups.${groupIndex}.conditions.${conditionIndex}.${field}` as Path<AlertFormType>;

const ConditionRow: React.FC<ConditionRowProps> = ({
  form,
  triggerIndex,
  groupIndex,
  conditionIndex,
  scoreSource,
  projectId,
  onDelete,
  canDelete,
}) => {
  const errorBase = [
    "triggers",
    triggerIndex,
    "groups",
    groupIndex,
    "conditions",
    conditionIndex,
  ] as const;
  const errors = Object.fromEntries(
    CONDITION_FIELDS.map((f) => [
      f,
      (
        get(form.formState.errors, [...errorBase, f]) as
          | { message?: string }
          | undefined
      )?.message,
    ]),
  ) as Record<ConditionField, string | undefined>;
  const hasErrors = CONDITION_FIELDS.some((f) => errors[f]);

  return (
    <div className="flex flex-col gap-1">
      <div className="flex min-h-11 items-stretch overflow-hidden rounded-md border border-border bg-background">
        <div className="flex min-w-0 flex-1 flex-wrap items-center gap-2 px-2 py-1.5">
          <FormField
            control={form.control}
            name={fieldPath(triggerIndex, groupIndex, conditionIndex, "name")}
            render={({ field }) => (
              <FormControl>
                <FeedbackDefinitionsAndScoresSelectBox
                  value={field.value as string}
                  onChange={field.onChange}
                  scoreSource={scoreSource}
                  entityIds={[projectId]}
                  multiselect={false}
                  className={cn("h-8 min-w-[160px] flex-1", {
                    "border-destructive": Boolean(errors.name),
                  })}
                />
              </FormControl>
            )}
          />
          <FormField
            control={form.control}
            name={fieldPath(
              triggerIndex,
              groupIndex,
              conditionIndex,
              "operator",
            )}
            render={({ field }) => (
              <FormControl>
                <ToggleGroup
                  type="single"
                  variant="secondary"
                  value={field.value as string}
                  onValueChange={(v) => v && field.onChange(v)}
                  className={cn("h-8 shrink-0", {
                    "border-destructive": Boolean(errors.operator),
                  })}
                >
                  {OPERATOR_VALUES.map((op) => (
                    <ToggleGroupItem
                      key={op}
                      value={op}
                      size="sm"
                      aria-label={op === ">" ? "greater than" : "less than"}
                    >
                      {op}
                    </ToggleGroupItem>
                  ))}
                </ToggleGroup>
              </FormControl>
            )}
          />
          <FormField
            control={form.control}
            name={fieldPath(
              triggerIndex,
              groupIndex,
              conditionIndex,
              "threshold",
            )}
            render={({ field }) => (
              <FormControl>
                <Input
                  className={cn("h-8 w-[87px] shrink-0 text-right", {
                    "border-destructive": Boolean(errors.threshold),
                  })}
                  type="number"
                  step="0.01"
                  placeholder="0.7"
                  value={field.value as string}
                  onChange={field.onChange}
                  onBlur={field.onBlur}
                  name={field.name}
                />
              </FormControl>
            )}
          />
          <FormField
            control={form.control}
            name={fieldPath(triggerIndex, groupIndex, conditionIndex, "window")}
            render={({ field }) => (
              <FormControl>
                <SelectBox
                  value={field.value as string}
                  onChange={field.onChange}
                  options={WINDOW_OPTIONS}
                  className={cn("h-8 min-w-[160px] flex-1 text-left", {
                    "border-destructive": Boolean(errors.window),
                  })}
                  placeholder="Select time window"
                  renderTrigger={(value) => {
                    const label = WINDOW_LABEL_BY_VALUE[value];
                    if (!label) return null;
                    return (
                      <span className="truncate">
                        <span className="text-muted-slate">In the last</span>{" "}
                        {label}
                      </span>
                    );
                  }}
                />
              </FormControl>
            )}
          />
        </div>
        <DisabledTooltip
          disabled={!canDelete}
          message="Can't remove the last condition — every group needs at least one."
        >
          <Button
            type="button"
            variant="minimal"
            size="icon-2xs"
            className="h-auto w-6 rounded-none border-l border-border opacity-50 hover:opacity-100"
            onClick={onDelete}
            disabled={!canDelete}
            aria-label="Remove condition"
          >
            <Trash />
          </Button>
        </DisabledTooltip>
      </div>
      {hasErrors && (
        <div className="flex flex-wrap gap-x-2 px-2 text-[0.8rem] font-medium text-destructive">
          {CONDITION_FIELDS.map(
            (f) => errors[f] && <span key={f}>{errors[f]}</span>,
          )}
        </div>
      )}
    </div>
  );
};

export default FeedbackScoreConditions;
