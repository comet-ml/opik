import React from "react";
import {
  ArrayPath,
  FieldValues,
  Path,
  useFieldArray,
  UseFormReturn,
} from "react-hook-form";
import { LayoutGrid, Plus, Trash } from "lucide-react";
import get from "lodash/get";

import { FormControl, FormField, FormItem } from "@/ui/form";
import { Input } from "@/ui/input";
import { Button } from "@/ui/button";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import SelectBox from "@/shared/SelectBox/SelectBox";
import FeedbackDefinitionsAndScoresSelectBox, {
  ScoreSource,
} from "@/v2/pages-shared/experiments/FeedbackDefinitionsAndScoresSelectBox/FeedbackDefinitionsAndScoresSelectBox";
import { cn } from "@/lib/utils";
import {
  DEFAULT_OPERATORS,
  OPERATOR_LABELS,
  OPERATOR_VALUES,
  OperatorValue,
  WINDOW_LABEL_BY_VALUE,
  WINDOW_OPTIONS,
} from "./constants";

/**
 * Score threshold condition builder: OR-ed groups of AND-ed conditions.
 *
 * Shared by alerts and annotation queue automation, which the design asks to look and behave
 * identically. The two differ in only three ways, all props here: alerts aggregate a score over a time
 * window and so show the window select, automation compares a single entity's score and does not; the
 * field path root differs; and the group icon is tinted per feature.
 *
 * The form is addressed by a runtime path string rather than a typed path. React Hook Form cannot
 * express "an array of groups somewhere in an arbitrary form shape" without the caller supplying a
 * literal, so the caller passes the root and this component builds children from it. That is the same
 * trade the alert-only version made with its literal casts.
 */
export type FeedbackScoreCondition = {
  name: string;
  operator: (typeof OPERATOR_VALUES)[number];
  threshold: string;
  window?: string;
};

export type FeedbackScoreConditionGroup = {
  conditions: FeedbackScoreCondition[];
};

/** Condition where the window is present — the shape callers that aggregate over a period require. */
export type WindowedFeedbackScoreCondition = FeedbackScoreCondition & {
  window: string;
};

export const DEFAULT_FEEDBACK_SCORE_CONDITION: WindowedFeedbackScoreCondition =
  {
    threshold: "",
    window: "86400",
    name: "",
    operator: ">",
  };

export const DEFAULT_FEEDBACK_SCORE_CONDITION_GROUP: {
  conditions: WindowedFeedbackScoreCondition[];
} = {
  conditions: [DEFAULT_FEEDBACK_SCORE_CONDITION],
};

/** Default for callers without a window, such as annotation queue automation. */
export const DEFAULT_UNWINDOWED_CONDITION: FeedbackScoreCondition = {
  threshold: "",
  name: "",
  operator: ">",
};

const ALL_CONDITION_FIELDS = [
  "name",
  "operator",
  "threshold",
  "window",
] as const;
type ConditionField = (typeof ALL_CONDITION_FIELDS)[number];

type SharedProps<T extends FieldValues> = {
  form: UseFormReturn<T>;
  /** Path to the groups array, e.g. `triggers.0.groups` or `automation.groups`. */
  groupsPath: string;
  scoreSource: ScoreSource;
  projectId: string;
  /** Time window select — meaningful only where the score is aggregated over a period. */
  showWindow?: boolean;
  /** Comparison operators to offer. Defaults to the pair the alerts API accepts. */
  operators?: OperatorValue[];
  /** Tailwind background for the group badge, so each feature can tint it. */
  groupIconClassName?: string;
  /** Shown when the last remaining group or condition cannot be deleted. */
  minimumMessage?: string;
  /**
   * Caps on how much can be added, where the caller's API enforces one. Left undefined by default
   * because alerts have no such limit - only annotation queue automation does, at 5 and 5 - and a cap
   * invented here would silently constrain a caller whose backend accepts more.
   */
  maxGroups?: number;
  maxConditionsPerGroup?: number;
};

const DEFAULT_MINIMUM_MESSAGE =
  "Can't remove — at least one group with one condition is required.";

// Radix tooltips don't fire on elements with pointer-events: none (which
// disabled buttons get from the Button variants), so when `disabled` is true
// we wrap the child in a span that intercepts hover/focus for the tooltip.
const DisabledTooltip: React.FC<{
  message: string;
  disabled: boolean;
  children: React.ReactNode;
}> = ({ message, disabled, children }) => (
  <TooltipWrapper content={disabled ? message : null}>
    <span className={cn("inline-flex", disabled && "cursor-not-allowed")}>
      {children}
    </span>
  </TooltipWrapper>
);

const SeparatorBadge: React.FC<{ kind: "AND" | "OR" }> = ({ kind }) => (
  <div className="py-0.5">
    <span className="text-xs font-medium leading-4 text-violet-600">
      {kind}
    </span>
  </div>
);

const FeedbackScoreConditions = <T extends FieldValues>({
  form,
  groupsPath,
  scoreSource,
  projectId,
  showWindow = false,
  operators = DEFAULT_OPERATORS,
  groupIconClassName = "bg-violet-600",
  minimumMessage = DEFAULT_MINIMUM_MESSAGE,
  maxGroups,
  maxConditionsPerGroup,
}: SharedProps<T>) => {
  const groupsFieldArray = useFieldArray({
    control: form.control,
    name: groupsPath as ArrayPath<T>,
  });

  const addGroup = () =>
    groupsFieldArray.append({
      conditions: [{ ...DEFAULT_FEEDBACK_SCORE_CONDITION }],
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } as any);

  const canDeleteGroup = groupsFieldArray.fields.length > 1;
  const atGroupLimit =
    maxGroups !== undefined && groupsFieldArray.fields.length >= maxGroups;

  return (
    <div className="flex flex-col gap-2">
      {groupsFieldArray.fields.map((group, groupIndex) => (
        <React.Fragment key={group.id}>
          {groupIndex > 0 && <SeparatorBadge kind="OR" />}
          <ConditionGroup
            form={form}
            groupsPath={groupsPath}
            groupIndex={groupIndex}
            scoreSource={scoreSource}
            projectId={projectId}
            showWindow={showWindow}
            operators={operators}
            groupIconClassName={groupIconClassName}
            minimumMessage={minimumMessage}
            maxConditionsPerGroup={maxConditionsPerGroup}
            label={`Group ${groupIndex + 1}`}
            onRemove={() => groupsFieldArray.remove(groupIndex)}
            canRemove={canDeleteGroup}
          />
        </React.Fragment>
      ))}
      <div className="flex h-8 items-center justify-center rounded-md border border-dashed border-border bg-soft-background">
        <DisabledTooltip
          disabled={atGroupLimit}
          message={`At most ${maxGroups} groups.`}
        >
          <Button
            type="button"
            variant="ghost"
            size="xs"
            className="text-foreground hover:text-primary-hover"
            onClick={addGroup}
            disabled={atGroupLimit}
          >
            <Plus className="mr-0.5 size-3" />
            Add OR group
          </Button>
        </DisabledTooltip>
      </div>
    </div>
  );
};

type ConditionGroupProps<T extends FieldValues> = SharedProps<T> & {
  groupIndex: number;
  label: string;
  onRemove: () => void;
  canRemove: boolean;
};

const ConditionGroup = <T extends FieldValues>({
  form,
  groupsPath,
  groupIndex,
  scoreSource,
  projectId,
  showWindow,
  operators = DEFAULT_OPERATORS,
  groupIconClassName,
  minimumMessage = DEFAULT_MINIMUM_MESSAGE,
  maxConditionsPerGroup,
  label,
  onRemove,
  canRemove,
}: ConditionGroupProps<T>) => {
  const conditionsFieldArray = useFieldArray({
    control: form.control,
    name: `${groupsPath}.${groupIndex}.conditions` as ArrayPath<T>,
  });

  const addCondition = () =>
    conditionsFieldArray.append({
      ...DEFAULT_FEEDBACK_SCORE_CONDITION,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } as any);

  // Deleting the only condition in a group removes the whole group (so the
  // user doesn't end up with an empty group), unless this is the last group
  // — then the delete is disabled to keep at least one group around.
  const handleDeleteCondition = (conditionIndex: number) => {
    if (conditionsFieldArray.fields.length === 1) {
      onRemove();
    } else {
      conditionsFieldArray.remove(conditionIndex);
    }
  };

  const canDeleteCondition =
    conditionsFieldArray.fields.length > 1 || canRemove;
  const atConditionLimit =
    maxConditionsPerGroup !== undefined &&
    conditionsFieldArray.fields.length >= maxConditionsPerGroup;

  return (
    <div className="overflow-hidden rounded-md border border-border bg-soft-background">
      <div className="flex h-8 items-center justify-between pl-2 pr-3">
        <div className="flex items-center gap-1.5">
          <span
            className={cn(
              "flex size-4 items-center justify-center rounded text-white",
              groupIconClassName,
            )}
          >
            <LayoutGrid className="size-2.5" />
          </span>
          <span className="text-xs font-medium leading-4 text-muted-slate">
            {label}
          </span>
        </div>
        <DisabledTooltip disabled={!canRemove} message={minimumMessage}>
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
              groupsPath={groupsPath}
              groupIndex={groupIndex}
              conditionIndex={conditionIndex}
              scoreSource={scoreSource}
              projectId={projectId}
              showWindow={showWindow}
              operators={operators}
              minimumMessage={minimumMessage}
              onDelete={() => handleDeleteCondition(conditionIndex)}
              canDelete={canDeleteCondition}
            />
          </React.Fragment>
        ))}
        <DisabledTooltip
          disabled={atConditionLimit}
          message={`At most ${maxConditionsPerGroup} conditions per group.`}
        >
          <Button
            type="button"
            variant="ghost"
            size="xs"
            className="self-start pl-1 text-foreground hover:text-primary-hover"
            onClick={addCondition}
            disabled={atConditionLimit}
          >
            <Plus className="mr-0.5 size-3" />
            Add AND condition
          </Button>
        </DisabledTooltip>
      </div>
    </div>
  );
};

type ConditionRowProps<T extends FieldValues> = Omit<
  SharedProps<T>,
  "groupIconClassName"
> & {
  groupIndex: number;
  conditionIndex: number;
  onDelete: () => void;
  canDelete: boolean;
};

const ConditionRow = <T extends FieldValues>({
  form,
  groupsPath,
  groupIndex,
  conditionIndex,
  scoreSource,
  projectId,
  showWindow,
  operators = DEFAULT_OPERATORS,
  minimumMessage = DEFAULT_MINIMUM_MESSAGE,
  onDelete,
  canDelete,
}: ConditionRowProps<T>) => {
  const conditionFields: readonly ConditionField[] = showWindow
    ? ALL_CONDITION_FIELDS
    : (["name", "operator", "threshold"] as const);

  const fieldPath = (field: ConditionField) =>
    `${groupsPath}.${groupIndex}.conditions.${conditionIndex}.${field}` as Path<T>;

  const errors = Object.fromEntries(
    conditionFields.map((f) => [
      f,
      (
        get(form.formState.errors, fieldPath(f).split(".")) as
          | { message?: string }
          | undefined
      )?.message,
    ]),
  ) as Record<ConditionField, string | undefined>;
  const hasErrors = conditionFields.some((f) => errors[f]);

  return (
    <div className="flex flex-col gap-1">
      <div className="flex min-h-11 items-stretch overflow-hidden rounded-md border border-border bg-background">
        <div className="flex min-w-0 flex-1 flex-wrap items-center gap-2 px-2 py-1.5">
          <FormField
            control={form.control}
            name={fieldPath("name")}
            render={({ field }) => (
              <FormItem className="flex min-w-[160px] flex-1">
                <FormControl>
                  <FeedbackDefinitionsAndScoresSelectBox
                    value={field.value as string}
                    onChange={field.onChange}
                    scoreSource={scoreSource}
                    entityIds={[projectId]}
                    multiselect={false}
                    className={cn("h-8 w-full font-normal", {
                      "border-destructive": Boolean(errors.name),
                    })}
                  />
                </FormControl>
              </FormItem>
            )}
          />
          <FormField
            control={form.control}
            name={fieldPath("operator")}
            render={({ field }) => (
              <FormItem className="shrink-0">
                <FormControl>
                  <ToggleGroup
                    type="single"
                    variant="secondary"
                    value={field.value as string}
                    onValueChange={(v) => v && field.onChange(v)}
                    className={cn("h-8", {
                      "border-destructive": Boolean(errors.operator),
                    })}
                  >
                    {operators.map((op) => (
                      <ToggleGroupItem
                        key={op}
                        value={op}
                        size="sm"
                        aria-label={OPERATOR_LABELS[op]}
                      >
                        {op}
                      </ToggleGroupItem>
                    ))}
                  </ToggleGroup>
                </FormControl>
              </FormItem>
            )}
          />
          <FormField
            control={form.control}
            name={fieldPath("threshold")}
            render={({ field }) => (
              <FormItem className="w-[87px] shrink-0">
                <FormControl>
                  <Input
                    className={cn("h-8 text-right", {
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
              </FormItem>
            )}
          />
          {showWindow && (
            <FormField
              control={form.control}
              name={fieldPath("window")}
              render={({ field }) => (
                <FormItem className="flex min-w-[160px] flex-1">
                  <FormControl>
                    <SelectBox
                      value={field.value as string}
                      onChange={field.onChange}
                      options={WINDOW_OPTIONS}
                      className={cn("h-8 w-full text-left font-normal", {
                        "border-destructive": Boolean(errors.window),
                      })}
                      placeholder="Select time window"
                      renderTrigger={(value) => {
                        const label = WINDOW_LABEL_BY_VALUE[value];
                        if (!label) return null;
                        return (
                          <span className="truncate">
                            <span className="text-muted-slate">
                              In the last
                            </span>{" "}
                            {label}
                          </span>
                        );
                      }}
                    />
                  </FormControl>
                </FormItem>
              )}
            />
          )}
        </div>
        <DisabledTooltip disabled={!canDelete} message={minimumMessage}>
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
        <div className="comet-body-s flex flex-wrap gap-x-2 px-2 text-destructive">
          {conditionFields.map(
            (f) => errors[f] && <span key={f}>{errors[f]}</span>,
          )}
        </div>
      )}
    </div>
  );
};

export default FeedbackScoreConditions;
