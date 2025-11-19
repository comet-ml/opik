import React from "react";
import { UseFormReturn } from "react-hook-form";

import { Label } from "@/components/ui/label";
import { FormErrorSkeleton, FormField, FormItem } from "@/components/ui/form";
import { Checkbox } from "@/components/ui/checkbox";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Description } from "@/components/ui/description";
import GitHubCallout from "@/components/shared/GitHubCallout/GitHubCallout";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import ScopeToggle from "./ScopeToggle";
import { AlertFormType } from "./schema";

type EventTriggersProps = {
  form: UseFormReturn<AlertFormType>;
};

const EventTriggers: React.FunctionComponent<EventTriggersProps> = ({
  form,
}) => {
  const eventTriggersError = form.formState.errors.eventTriggers;

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h3 className="text-sm font-medium">Triggers</h3>
        <Description>
          Choose which events should trigger this alert.
        </Description>
      </div>

      <div className="flex flex-col gap-4">
        {/* Errors and issues */}
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium">
              Errors and issues
            </CardTitle>
          </CardHeader>
          <CardContent className="pt-0">
            <div className="flex flex-col gap-3">
              <ScopeToggle
                form={form}
                toggleFieldName="traceErrorScopeToggle"
                scopeFieldName="traceErrorScope"
              />
              {form.watch("eventTriggers.traceErrorScopeToggle") === false && (
                <FormField
                  control={form.control}
                  name="eventTriggers.traceErrorScope"
                  render={({ field }) => (
                    <FormItem>
                      <ProjectsSelectBox
                        value={field.value}
                        onChange={field.onChange}
                        multiselect={true}
                        className="h-8"
                      />
                    </FormItem>
                  )}
                />
              )}
              <div className="ml-1 flex flex-col gap-2">
                <h4 className="text-xs font-medium text-slate-500">Events</h4>
                <FormField
                  control={form.control}
                  name="eventTriggers.traceErrorNewError"
                  render={({ field }) => (
                    <div className="flex items-center space-x-2">
                      <Checkbox
                        id="traceErrorNewError"
                        checked={field.value}
                        onCheckedChange={field.onChange}
                      />
                      <Label
                        htmlFor="traceErrorNewError"
                        className="cursor-pointer text-sm font-normal"
                      >
                        New error in the trace
                      </Label>
                    </div>
                  )}
                />
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Guardrails */}
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium">Guardrails</CardTitle>
          </CardHeader>
          <CardContent className="pt-0">
            <div className="flex flex-col gap-3">
              <ScopeToggle
                form={form}
                toggleFieldName="guardrailScopeToggle"
                scopeFieldName="guardrailScope"
              />
              {form.watch("eventTriggers.guardrailScopeToggle") === false && (
                <FormField
                  control={form.control}
                  name="eventTriggers.guardrailScope"
                  render={({ field }) => (
                    <FormItem>
                      <ProjectsSelectBox
                        value={field.value}
                        onChange={field.onChange}
                        multiselect={true}
                        className="h-8"
                      />
                    </FormItem>
                  )}
                />
              )}
              <div className="ml-1 flex flex-col gap-2">
                <h4 className="text-xs font-medium text-slate-500">Events</h4>
                <FormField
                  control={form.control}
                  name="eventTriggers.guardrailTriggered"
                  render={({ field }) => (
                    <div className="flex items-center space-x-2">
                      <Checkbox
                        id="guardrailTriggered"
                        checked={field.value}
                        onCheckedChange={field.onChange}
                      />
                      <Label
                        htmlFor="guardrailTriggered"
                        className="cursor-pointer text-sm font-normal"
                      >
                        Guardrail is triggered
                      </Label>
                    </div>
                  )}
                />
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Feedback scores */}
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium">
              Feedback scores
            </CardTitle>
          </CardHeader>
          <CardContent className="pt-0">
            <div className="flex flex-col gap-3">
              <ScopeToggle
                form={form}
                toggleFieldName="feedbackScoreScopeToggle"
                scopeFieldName="feedbackScoreScope"
              />
              {form.watch("eventTriggers.feedbackScoreScopeToggle") ===
                false && (
                <FormField
                  control={form.control}
                  name="eventTriggers.feedbackScoreScope"
                  render={({ field }) => (
                    <FormItem>
                      <ProjectsSelectBox
                        value={field.value}
                        onChange={field.onChange}
                        multiselect={true}
                        className="h-8"
                      />
                    </FormItem>
                  )}
                />
              )}
              <div className="ml-1 flex flex-col gap-2">
                <h4 className="text-xs font-medium text-slate-500">Events</h4>
                <FormField
                  control={form.control}
                  name="eventTriggers.feedbackScoreNewTrace"
                  render={({ field }) => (
                    <div className="flex items-center space-x-2">
                      <Checkbox
                        id="feedbackScoreNewTrace"
                        checked={field.value}
                        onCheckedChange={field.onChange}
                      />
                      <Label
                        htmlFor="feedbackScoreNewTrace"
                        className="cursor-pointer text-sm font-normal"
                      >
                        New score added to trace
                      </Label>
                    </div>
                  )}
                />
                <FormField
                  control={form.control}
                  name="eventTriggers.feedbackScoreNewThread"
                  render={({ field }) => (
                    <div className="flex items-center space-x-2">
                      <Checkbox
                        id="feedbackScoreNewThread"
                        checked={field.value}
                        onCheckedChange={field.onChange}
                      />
                      <Label
                        htmlFor="feedbackScoreNewThread"
                        className="cursor-pointer text-sm font-normal"
                      >
                        New score added to thread
                      </Label>
                    </div>
                  )}
                />
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Prompt library */}
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-medium">
              Prompt library
            </CardTitle>
          </CardHeader>
          <CardContent className="pt-0">
            <div className="ml-1 flex flex-col gap-2">
              <h4 className="text-xs font-medium text-slate-500">Events</h4>
              <FormField
                control={form.control}
                name="eventTriggers.promptLibraryNewPrompt"
                render={({ field }) => (
                  <div className="flex items-center space-x-2">
                    <Checkbox
                      id="promptLibraryNewPrompt"
                      checked={field.value}
                      onCheckedChange={field.onChange}
                    />
                    <Label
                      htmlFor="promptLibraryNewPrompt"
                      className="cursor-pointer text-sm font-normal"
                    >
                      New prompt is added to the Prompt library
                    </Label>
                  </div>
                )}
              />
              <FormField
                control={form.control}
                name="eventTriggers.promptLibraryNewCommit"
                render={({ field }) => (
                  <div className="flex items-center space-x-2">
                    <Checkbox
                      id="promptLibraryNewCommit"
                      checked={field.value}
                      onCheckedChange={field.onChange}
                    />
                    <Label
                      htmlFor="promptLibraryNewCommit"
                      className="cursor-pointer text-sm font-normal"
                    >
                      New commit is created
                    </Label>
                  </div>
                )}
              />
            </div>
          </CardContent>
        </Card>
      </div>
      {eventTriggersError && (
        <FormErrorSkeleton>
          {eventTriggersError.message || eventTriggersError.root?.message}
        </FormErrorSkeleton>
      )}

      <GitHubCallout description="Missing a trigger?" />
    </div>
  );
};

export default EventTriggers;
