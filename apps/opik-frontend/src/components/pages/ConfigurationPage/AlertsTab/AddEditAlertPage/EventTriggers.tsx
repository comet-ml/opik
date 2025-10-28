import React, { useMemo } from "react";
import { Path, useFieldArray, UseFormReturn } from "react-hook-form";
import { CircleHelp, ExternalLink, Plus, WebhookIcon, X } from "lucide-react";

import { Label } from "@/components/ui/label";
import { FormErrorSkeleton, FormField, FormItem } from "@/components/ui/form";
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
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import { AlertFormType } from "./schema";
import { TRIGGER_CONFIG } from "./helpers";
import { ALERT_EVENT_TYPE } from "@/types/alerts";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";

type EventTriggersProps = {
  form: UseFormReturn<AlertFormType>;
  projectsIds: string[];
};

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

                return (
                  <div key={field.id}>
                    <div className="flex items-stretch gap-4">
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
