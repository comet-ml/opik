import React, { useCallback, useMemo, useState } from "react";
import { Path, useFieldArray, UseFormReturn } from "react-hook-form";
import { CircleHelp, ExternalLink, Plus, WebhookIcon, X } from "lucide-react";
import get from "lodash/get";
import { keepPreviousData } from "@tanstack/react-query";

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
import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useTracesFeedbackScoresNames from "@/api/traces/useTracesFeedbackScoresNames";
import useThreadsFeedbackScoresNames from "@/api/traces/useThreadsFeedbackScoresNames";
import { DropdownOption } from "@/types/shared";
import { AlertFormType } from "./schema";
import { TRIGGER_CONFIG } from "./helpers";
import { ALERT_EVENT_TYPE } from "@/types/alerts";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { cn } from "@/lib/utils";
import useAppStore from "@/store/AppStore";

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

const OPERATOR_OPTIONS: DropdownOption<string>[] = [
  { label: ">", value: ">" },
  { label: "<", value: "<" },
];

const DEFAULT_LOADED_FEEDBACK_DEFINITION_ITEMS = 1000;

interface FeedbackScoreNameSelectorProps {
  value: string;
  onChange: (value: string) => void;
  className?: string;
  eventType: ALERT_EVENT_TYPE;
}

const FeedbackScoreNameSelector: React.FC<FeedbackScoreNameSelectorProps> = ({
  value,
  onChange,
  className,
  eventType,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [isLoadedMore, setIsLoadedMore] = useState(false);

  // Fetch feedback definitions
  const { data: feedbackDefinitionsData, isLoading: isLoadingDefinitions } =
    useFeedbackDefinitionsList(
      {
        workspaceName,
        page: 1,
        size: isLoadedMore ? 10000 : DEFAULT_LOADED_FEEDBACK_DEFINITION_ITEMS,
      },
      {
        placeholderData: keepPreviousData,
      },
    );

  // Fetch actual feedback score names based on trigger type
  const isTraceTrigger = eventType === ALERT_EVENT_TYPE.trace_feedback_score;
  const isThreadTrigger =
    eventType === ALERT_EVENT_TYPE.trace_thread_feedback_score;

  const { data: traceScoresData, isLoading: isLoadingTraceScores } =
    useTracesFeedbackScoresNames(
      { projectId: undefined },
      { enabled: isTraceTrigger },
    );

  const { data: threadScoresData, isLoading: isLoadingThreadScores } =
    useThreadsFeedbackScoresNames(
      { projectId: undefined },
      { enabled: isThreadTrigger },
    );

  const total = feedbackDefinitionsData?.total ?? 0;

  const loadMoreHandler = useCallback(() => setIsLoadedMore(true), []);

  // Merge feedback definitions and actual feedback score names
  const options: DropdownOption<string>[] = useMemo(() => {
    const definitionNames = new Map<
      string,
      { name: string; description?: string }
    >();

    // Add feedback definitions
    (feedbackDefinitionsData?.content || []).forEach((def) => {
      definitionNames.set(def.name, {
        name: def.name,
        description: def.description,
      });
    });

    // Add actual feedback score names from appropriate source
    const scoreNames = isTraceTrigger
      ? traceScoresData?.scores || []
      : isThreadTrigger
        ? threadScoresData?.scores || []
        : [];

    scoreNames.forEach((score) => {
      if (!definitionNames.has(score.name)) {
        definitionNames.set(score.name, { name: score.name });
      }
    });

    // Convert to dropdown options, sorted alphabetically
    return Array.from(definitionNames.values())
      .sort((a, b) => a.name.localeCompare(b.name))
      .map((item) => ({
        value: item.name,
        label: item.name,
        description: item.description,
      }));
  }, [
    feedbackDefinitionsData?.content,
    traceScoresData?.scores,
    threadScoresData?.scores,
    isTraceTrigger,
    isThreadTrigger,
  ]);

  const isLoading =
    isLoadingDefinitions ||
    (isTraceTrigger && isLoadingTraceScores) ||
    (isThreadTrigger && isLoadingThreadScores);

  return (
    <LoadableSelectBox
      value={value}
      onChange={onChange}
      options={options}
      onLoadMore={
        total > DEFAULT_LOADED_FEEDBACK_DEFINITION_ITEMS && !isLoadedMore
          ? loadMoreHandler
          : undefined
      }
      buttonClassName={className}
      isLoading={isLoading}
      optionsCount={DEFAULT_LOADED_FEEDBACK_DEFINITION_ITEMS}
      placeholder="Select score"
    />
  );
};

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
      append({
        eventType,
        projectIds: projectsIds,
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
      <div className="flex flex-wrap items-end gap-2">
        <Label className="comet-body-s self-center text-muted-slate">
          When
        </Label>
        <FormField
          control={form.control}
          name={`triggers.${index}.name` as Path<AlertFormType>}
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, [
              "triggers",
              index,
              "name",
            ]);
            return (
              <FormItem className="min-w-[150px] flex-1">
                <FormControl>
                  <FeedbackScoreNameSelector
                    value={field.value as string}
                    onChange={field.onChange}
                    eventType={eventType}
                    className={cn("h-8", {
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            );
          }}
        />
        <FormField
          control={form.control}
          name={`triggers.${index}.operator` as Path<AlertFormType>}
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, [
              "triggers",
              index,
              "operator",
            ]);
            return (
              <FormItem className="w-16">
                <FormControl>
                  <SelectBox
                    value={field.value as string}
                    onChange={field.onChange}
                    options={OPERATOR_OPTIONS}
                    className={cn("h-8 text-left", {
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                    placeholder=">"
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            );
          }}
        />
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
              <FormItem className="w-24">
                <FormControl>
                  <Input
                    className={cn("h-8", {
                      "border-destructive": Boolean(validationErrors?.message),
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
                <FormMessage />
              </FormItem>
            );
          }}
        />
        <Label className="comet-body-s self-center text-muted-slate">
          in the last
        </Label>
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
              <FormItem className="min-w-[120px] flex-1">
                <FormControl>
                  <SelectBox
                    value={field.value as string}
                    onChange={field.onChange}
                    options={WINDOW_OPTIONS}
                    className={cn("h-8 text-left", {
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
