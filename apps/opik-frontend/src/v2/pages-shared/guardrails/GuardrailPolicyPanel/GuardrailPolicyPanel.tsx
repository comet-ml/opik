import React, { useCallback } from "react";
import { ArrowUpRight, TriangleAlert } from "lucide-react";

import { Button } from "@/ui/button";
import { Description } from "@/ui/description";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { RadioGroup, RadioGroupItem } from "@/ui/radio-group";
import { Separator } from "@/ui/separator";
import { Spinner } from "@/ui/spinner";
import { Textarea } from "@/ui/textarea";
import {
  Form,
  FormControl,
  FormErrorSkeleton,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/ui/form";
import { cn } from "@/lib/utils";
import { formatDate } from "@/lib/date";
import { buildDocsUrl } from "@/v2/lib/utils";
import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import ResizableSidePanelTopBar from "@/shared/ResizableSidePanel/ResizableSidePanelTopBar";
import RuleConfig from "@/v2/pages-shared/guardrails/RuleConfig/RuleConfig";
import GuardrailPolicyCodeSection from "@/v2/pages-shared/guardrails/GuardrailPolicyPanel/GuardrailPolicyCodeSection";
import useGuardrailPolicyForm, {
  GuardrailPolicyPanelMode,
} from "@/v2/pages-shared/guardrails/GuardrailPolicyPanel/useGuardrailPolicyForm";
import {
  POLICY_DESCRIPTION_MAX_LENGTH,
  POLICY_NAME_MAX_LENGTH,
} from "@/v2/pages-shared/guardrails/GuardrailPolicyPanel/schema";
import {
  GUARDRAIL_EXECUTION_SCOPE,
  GuardrailPolicy,
  GuardrailTypes,
} from "@/types/guardrails";
import {
  GuardrailExecutionScopeDescriptionMap,
  GuardrailExecutionScopeLabelMap,
} from "@/constants/guardrails";

const DOCS_URL = buildDocsUrl("/production/gateway-guardrails/guardrails");

const FORM_ID = "guardrail-policy-form";

const EXECUTION_SCOPES = [
  GUARDRAIL_EXECUTION_SCOPE.ON_REQUEST,
  GUARDRAIL_EXECUTION_SCOPE.GLOBAL,
];

const TITLE_MAP: Record<GuardrailPolicyPanelMode, string> = {
  create: "Create policy",
  edit: "Edit policy",
  duplicate: "Duplicate policy",
};

type GuardrailPolicyPanelProps = {
  mode: GuardrailPolicyPanelMode;
  policy?: GuardrailPolicy;
  /** Names already taken, so a duplicate opens under one that does not collide. */
  takenNames?: string[];
  open: boolean;
  setOpen: (open: boolean) => void;
};

/**
 * Creating and editing a policy are the same form. There is no detail page, so this panel is also
 * where an existing policy's audit fields are shown.
 */
const GuardrailPolicyPanel: React.FunctionComponent<
  GuardrailPolicyPanelProps
> = ({ mode, policy, takenNames, open, setOpen }) => {
  const {
    form,
    rulesError,
    isRenamed,
    isEdit,
    isDirty,
    isSubmitting,
    submitHandler,
  } = useGuardrailPolicyForm({ mode, policy, takenNames, open, setOpen });

  const handleClose = useCallback(() => setOpen(false), [setOpen]);

  const policyName = form.watch("name");

  return (
    <ResizableSidePanel
      panelId="guardrail-policy-panel"
      entity="policy"
      open={open}
      onClose={handleClose}
      initialWidth={0.5}
      minWidth={520}
      // Clicking away closes the panel, except while there is unsaved work to lose: the same
      // condition that enables Save.
      blockOverlayClose={isDirty}
      header={
        <ResizableSidePanelTopBar
          variant="form"
          title={
            <span className="comet-body-s-accented">{TITLE_MAP[mode]}</span>
          }
          onClose={handleClose}
        >
          <Button variant="outline" size="2xs" asChild>
            <a href={DOCS_URL} target="_blank" rel="noopener noreferrer">
              Docs
              <ArrowUpRight className="ml-1 size-3 shrink-0" />
            </a>
          </Button>
        </ResizableSidePanelTopBar>
      }
    >
      <Form {...form}>
        <div className="flex size-full flex-col">
          <div className="flex-1 overflow-y-auto px-9 pb-6 pt-4">
            {/* The form element covers the text fields, so Enter submits. The rules and the code
              snippet sit outside it: the snippet's copy button sets no button type and would
              submit the form. */}
            <form
              id={FORM_ID}
              className="flex flex-col gap-4"
              onSubmit={submitHandler}
            >
              <FormField
                control={form.control}
                name="name"
                render={({ field, fieldState }) => (
                  <FormItem className="gap-1.5">
                    <FormLabel>Name</FormLabel>
                    <FormControl>
                      <Input
                        dimension="sm"
                        placeholder="Name your policy..."
                        maxLength={POLICY_NAME_MAX_LENGTH}
                        className={cn(
                          fieldState.error &&
                            "!border-destructive focus-visible:!border-destructive",
                        )}
                        {...field}
                      />
                    </FormControl>
                    <FormMessage />
                    {isRenamed && (
                      <div className="comet-body-xs flex items-start gap-2 rounded-md bg-warning-box-bg p-2 text-warning-box-text">
                        <TriangleAlert className="mt-0.5 size-3.5 shrink-0 text-warning-box-icon-text" />
                        <span>
                          Applications reference a policy by name. Renaming this
                          one breaks any application still using &quot;
                          {policy?.name}&quot;.
                        </span>
                      </div>
                    )}
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="description"
                render={({ field }) => (
                  <FormItem className="gap-1.5">
                    <FormLabel>
                      Description{" "}
                      <span className="font-normal text-foreground">
                        (optional)
                      </span>
                    </FormLabel>
                    <FormControl>
                      <Textarea
                        placeholder="What this policy protects against"
                        className="min-h-16 text-sm"
                        maxLength={POLICY_DESCRIPTION_MAX_LENGTH}
                        {...field}
                      />
                    </FormControl>
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="executionScope"
                render={({ field }) => (
                  <FormItem className="gap-1.5">
                    <FormLabel>Execution scope</FormLabel>
                    {/* Radios rather than a segmented control: each scope carries an explanation,
                      and both explanations being visible at once is what makes the choice obvious. */}
                    <RadioGroup
                      value={field.value}
                      onValueChange={field.onChange}
                    >
                      {EXECUTION_SCOPES.map((value) => (
                        <Label
                          key={value}
                          className="flex cursor-pointer items-start gap-2 font-normal"
                        >
                          <RadioGroupItem value={value} className="mt-0.5" />
                          <div>
                            <div className="comet-body-s-accented text-foreground">
                              {GuardrailExecutionScopeLabelMap[value]}
                            </div>
                            <Description>
                              {GuardrailExecutionScopeDescriptionMap[value]}
                            </Description>
                          </div>
                        </Label>
                      ))}
                    </RadioGroup>
                  </FormItem>
                )}
              />
            </form>

            <Separator className="my-4" />

            {/* A heading, not a Label: a rule's own title is a Label, so the section needs to
              outrank it. */}
            <h2 className="comet-title-xs">Rules</h2>
            <Description className="mt-1 block">
              Switch on the checks this policy runs. A policy holds at most one
              rule of each type.
            </Description>

            <div className="mt-2 divide-y">
              <FormField
                control={form.control}
                name={`rules.${GuardrailTypes.PII}.enabled`}
                render={({ field }) => (
                  <RuleConfig
                    type={GuardrailTypes.PII}
                    enabled={field.value}
                    toggleEnabled={() => field.onChange(!field.value)}
                  >
                    <FormField
                      control={form.control}
                      name={`rules.${GuardrailTypes.PII}.entities`}
                      render={({ field: entities, fieldState }) => (
                        <RuleConfig.PIIEntities
                          value={entities.value}
                          error={fieldState.error?.message}
                          onChange={entities.onChange}
                        />
                      )}
                    />
                  </RuleConfig>
                )}
              />

              <FormField
                control={form.control}
                name={`rules.${GuardrailTypes.TOPIC}.enabled`}
                render={({ field }) => (
                  <RuleConfig
                    type={GuardrailTypes.TOPIC}
                    enabled={field.value}
                    toggleEnabled={() => field.onChange(!field.value)}
                  >
                    <FormField
                      control={form.control}
                      name={`rules.${GuardrailTypes.TOPIC}.restrictedTopics`}
                      render={({ field: restricted, fieldState }) => (
                        <RuleConfig.TopicsList
                          id="topic-restricted"
                          label="Restricted topics"
                          hint="The check fails when the text is about any of these."
                          value={restricted.value}
                          error={fieldState.error?.message}
                          onChange={restricted.onChange}
                        />
                      )}
                    />
                    <FormField
                      control={form.control}
                      name={`rules.${GuardrailTypes.TOPIC}.allowedTopics`}
                      render={({ field: allowed }) => (
                        <RuleConfig.TopicsList
                          id="topic-allowed"
                          label="Allowed topics"
                          hint="The check fails when the text is about none of these."
                          value={allowed.value}
                          onChange={allowed.onChange}
                        />
                      )}
                    />
                  </RuleConfig>
                )}
              />

              <FormField
                control={form.control}
                name={`rules.${GuardrailTypes.PROMPT_INJECTION}.enabled`}
                render={({ field }) => (
                  <RuleConfig
                    type={GuardrailTypes.PROMPT_INJECTION}
                    enabled={field.value}
                    toggleEnabled={() => field.onChange(!field.value)}
                  />
                )}
              />
            </div>

            <div className="mt-4">
              <GuardrailPolicyCodeSection policyName={policyName} />
            </div>

            {isEdit && policy && (
              <>
                <Separator className="my-4" />
                <div className="comet-body-xs flex flex-col gap-1 text-light-slate">
                  <span>
                    Created by {policy.created_by} on{" "}
                    {formatDate(policy.created_at)}
                  </span>
                  <span>
                    Last updated by {policy.last_updated_by} on{" "}
                    {formatDate(policy.last_updated_at)}
                  </span>
                </div>
              </>
            )}
          </div>

          <div className="flex flex-col gap-2 border-t px-9 py-4">
            {rulesError && <FormErrorSkeleton>{rulesError}</FormErrorSkeleton>}
            <div className="flex items-center justify-end gap-2">
              <Button
                type="button"
                variant="outline"
                onClick={handleClose}
                disabled={isSubmitting}
              >
                Cancel
              </Button>
              <Button
                type="submit"
                form={FORM_ID}
                disabled={isSubmitting || !isDirty}
              >
                {isSubmitting && <Spinner size="small" className="mr-2" />}
                {isEdit ? "Save changes" : "Create policy"}
              </Button>
            </div>
          </div>
        </div>
      </Form>
    </ResizableSidePanel>
  );
};

export default GuardrailPolicyPanel;
