import React, { useMemo } from "react";
import { Path, useFieldArray, UseFormReturn } from "react-hook-form";
import { CircleHelp, ExternalLink, Plus, WebhookIcon, X } from "lucide-react";
import get from "lodash/get";

import { Label } from "@/components/ui/label";
import {
  FormControl,
  FormErrorSkeleton,
  FormField,
  FormItem,
  FormMessage,
} from "@/components/ui/form";
import { Checkbox } from "@/components/ui/checkbox";
import { Card, CardContent } from "@/components/ui/card";
import { Description } from "@/components/ui/description";
import { Button } from "@/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Separator } from "@/components/ui/separator";
import { Input } from "@/components/ui/input";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import { DropdownOption } from "@/types/shared";
import { AlertFormType } from "./schema";
import { TRIGGER_CONFIG } from "./helpers";
import { ALERT_EVENT_TYPE } from "@/types/alerts";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { cn } from "@/lib/utils";
import FeedbackScoreConditions, {
  DEFAULT_FEEDBACK_SCORE_CONDITION,
} from "./FeedbackScoreConditions";

type EventTriggersProps = {
  form: UseFormReturn<AlertFormType>;
  projectsIds: string[];
};

const WINDOW_OPTIONS: DropdownOption<string>[] = [
  { label: "5 minutes", value: "300" },
  { label: "15 minutes", value: "900" },
  { label: "30 minutes", value: "1800" },
  { label: "1 hour", value: "3600" },
  { label: "6 hours", value: "21600" },
  { label: "12 hours", value: "43200" },
  { label: "24 hours", value: "86400" },
  { label: "7 days", value: "604800" },
  { label: "15 days", value: "1296000" },
  { label: "30 days", value: "2592000" },
];

function getThresholdLabel(eventType: ALERT_EVENT_TYPE): string {
  switch (eventType) {
    case ALERT_EVENT_TYPE.trace_cost:
      return "Total cost exceeds (USD)";
    case ALERT_EVENT_TYPE.trace_errors:
      return "Trace errors count exceeds";
    case ALERT_EVENT_TYPE.trace_latency:
      return "Average latency exceeds (seconds)";
    default:
      return "Threshold exceeds";
  }
}

function getThresholdPlaceholder(eventType: ALERT_EVENT_TYPE): string {
  switch (eventType) {
    case ALERT_EVENT_TYPE.trace_cost:
      return "100";
    case ALERT_EVENT_TYPE.trace_errors:
      return "10";
    case ALERT_EVENT_TYPE.trace_latency:
      return "0.0";
    default:
      return "0";
  }
}

const EventTriggers: React.FunctionComponent<EventTriggersProps> = ({
  form,
  projectsIds,
}) => {
  const triggersError = form.formState.errors.triggers;
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );

  const { fields, append, remove } = useFieldArray({
    control: form.control,
    name: "triggers",
  });

  const selectedEventTypes = useMemo(() => {
    return new Set(fields.map((f) => f.eventType));
  }, [fields]);

  const hasTriggers = fields.length > 0;

  const toggleTrigger = (eventType: ALERT_EVENT_TYPE, checked: boolean) => {
    if (checked) {
      const isFeedbackScoreTrigger =
        eventType === ALERT_EVENT_TYPE.trace_feedback_score ||
        eventType === ALERT_EVENT_TYPE.trace_thread_feedback_score;

      append({
        eventType,
        projectIds: projectsIds,
        ...(isFeedbackScoreTrigger
          ? {
              conditions: [DEFAULT_FEEDBACK_SCORE_CONDITION],
            }
          : {}),
      });
    } else {
      const index = fields.findIndex((f) => f.eventType === eventType);
      if (index >= 0) {
        remove(index);
      }
    }
  };

  const removeTrigger = (index: number) => {
    remove(index);
  };

  const renderThresholdConfig = (
    index: number,
    eventType: ALERT_EVENT_TYPE,
  ) => {
    return (
      <div className="flex items-start gap-4">
        <FormField
          control={form.control}
          name={`triggers.${index}.threshold` as Path<AlertFormType>}
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, [
              "triggers",
              index,
              "threshold",
            ]);
            return (
              <FormItem className="flex-1">
                <Label className="comet-body-s">
                  {getThresholdLabel(eventType)}
                </Label>
                <FormControl>
                  <Input
                    className={cn("h-8", {
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                    type="number"
                    step="any"
                    placeholder={getThresholdPlaceholder(eventType)}
                    value={field.value as string}
                    onChange={field.onChange}
                    onBlur={field.onBlur}
                    name={field.name}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            );
          }}
        />
        <FormField
          control={form.control}
          name={`triggers.${index}.window` as Path<AlertFormType>}
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, [
              "triggers",
              index,
              "window",
            ]);
            return (
              <FormItem className="flex-1">
                <Label className="comet-body-s">In the last</Label>
                <FormControl>
                  <SelectBox
                    value={field.value as string}
                    onChange={field.onChange}
                    options={WINDOW_OPTIONS}
                    className={cn("h-8", {
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                    placeholder="Select time window"
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            );
          }}
        />
      </div>
    );
  };

  const renderFeedbackScoreThresholdConfig = (
    index: number,
    eventType: ALERT_EVENT_TYPE,
  ) => {
    return (
      <FeedbackScoreConditions
        form={form}
        triggerIndex={index}
        eventType={eventType}
      />
    );
  };

  const allEventTypes = useMemo(() => {
    const eventTypes = Object.values(ALERT_EVENT_TYPE) as ALERT_EVENT_TYPE[];
    return eventTypes.filter((t) =>
      t === ALERT_EVENT_TYPE.trace_guardrails_triggered
        ? isGuardrailsEnabled
        : true,
    );
  }, [isGuardrailsEnabled]);

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <div className="flex flex-col gap-1">
          <h3 className="comet-body-accented">Triggers</h3>
          <Description>
            Choose which platform events will trigger the alert.
          </Description>
        </div>

        <Popover>
          <PopoverTrigger asChild>
            <Button type="button" variant="outline" size="sm">
              <Plus className="mr-1 size-3" />
              Add trigger
            </Button>
          </PopoverTrigger>
          <PopoverContent className="w-[480px] p-0" align="end">
            <div className="flex flex-col">
              <div className="max-h-[400px] overflow-y-auto p-1">
                {allEventTypes.map((eventType) => {
                  const config = TRIGGER_CONFIG[eventType];
                  const isChecked = selectedEventTypes.has(eventType);

                  return (
                    <label
                      key={eventType}
                      className="flex min-w-[200px] cursor-pointer flex-col gap-0.5 rounded px-3 py-2.5 hover:bg-muted"
                    >
                      <div className="flex items-center gap-2">
                        <Checkbox
                          checked={isChecked}
                          onCheckedChange={(checked) =>
                            toggleTrigger(eventType, !!checked)
                          }
                        />
                        <span className="comet-body-s-accented flex-1">
                          {config.title}
                        </span>
                      </div>
                      <Description className="pl-7">
                        {config.description}
                      </Description>
                    </label>
                  );
                })}
              </div>

              <Separator />

              <div className="flex min-w-[200px] items-center gap-2 rounded px-4 py-2.5">
                <CircleHelp className="size-4 shrink-0 text-muted-foreground" />
                <div className="flex flex-wrap items-center gap-1 text-sm">
                  <span className="comet-body-s">
                    Missing a trigger? Open a
                  </span>
                  <a
                    href="https://github.com/comet-ml/opik/issues/new"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center gap-1 text-primary hover:underline"
                    onClick={(e) => e.stopPropagation()}
                  >
                    <span className="comet-body-s">GitHub ticket</span>
                    <ExternalLink className="size-3.5" />
                  </a>
                  <span className="comet-body-s">to let us know!</span>
                </div>
              </div>
            </div>
          </PopoverContent>
        </Popover>
      </div>

      {!hasTriggers && (
        <Card>
          <CardContent className="p-4">
            <div className="flex flex-col items-center justify-center gap-2 py-8">
              <WebhookIcon className="size-4 text-muted-foreground" />
              <p className="comet-body-s-accented text-center">
                No triggers selected yet
              </p>
              <p className="comet-body-s text-center text-muted-foreground">
                Choose one or more events to decide when this webhook should be
                activated
              </p>
            </div>
          </CardContent>
        </Card>
      )}

      {hasTriggers && (
        <Card>
          <CardContent className="p-4">
            <div className="flex flex-col gap-2">
              {fields.map((field, index) => {
                const config = TRIGGER_CONFIG[field.eventType];
                const isLastItem = index === fields.length - 1;
                const isThresholdTrigger =
                  field.eventType === ALERT_EVENT_TYPE.trace_cost ||
                  field.eventType === ALERT_EVENT_TYPE.trace_latency ||
                  field.eventType === ALERT_EVENT_TYPE.trace_errors;
                const isFeedbackScoreTrigger =
                  field.eventType === ALERT_EVENT_TYPE.trace_feedback_score ||
                  field.eventType ===
                    ALERT_EVENT_TYPE.trace_thread_feedback_score;

                return (
                  <div key={field.id}>
                    <div className="flex items-stretch gap-4">
                      <div className="flex flex-auto flex-col gap-3">
                        <div className="flex gap-4">
                          <div className="flex flex-1 flex-col gap-1">
                            <Label className="comet-body-s-accented">
                              {config.title}
                            </Label>
                            <Description>{config.description}</Description>
                          </div>

                          {config.hasScope && (
                            <FormField
                              control={form.control}
                              name={
                                `triggers.${index}.projectIds` as Path<AlertFormType>
                              }
                              render={({ field }) => (
                                <FormItem className="justify-center">
                                  <ProjectsSelectBox
                                    value={field.value as string[]}
                                    onValueChange={field.onChange}
                                    multiselect={true}
                                    className="h-8 w-40"
                                    showSelectAll={true}
                                    minWidth={204}
                                  />
                                </FormItem>
                              )}
                            />
                          )}
                        </div>
                        {isThresholdTrigger &&
                          renderThresholdConfig(index, field.eventType)}
                        {isFeedbackScoreTrigger &&
                          renderFeedbackScoreThresholdConfig(
                            index,
                            field.eventType,
                          )}
                      </div>
                      <div className="flex items-center">
                        <Button
                          type="button"
                          variant="minimal"
                          size="icon-xs"
                          onClick={() => removeTrigger(index)}
                        >
                          <X />
                        </Button>
                      </div>
                    </div>
                    {!isLastItem && <Separator className="my-2" />}
                  </div>
                );
              })}
            </div>
          </CardContent>
        </Card>
      )}

      {triggersError && (
        <FormErrorSkeleton>
          {String(triggersError.message || triggersError.root?.message || "")}
        </FormErrorSkeleton>
      )}
    </div>
  );
};

export default EventTriggers;
