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
import { FormErrorSkeleton } from "@/ui/form";
import { cn } from "@/lib/utils";
import { formatDate } from "@/lib/date";
import { buildDocsUrl } from "@/v2/lib/utils";
import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import ResizableSidePanelTopBar from "@/shared/ResizableSidePanel/ResizableSidePanelTopBar";
import RuleConfig from "@/v2/pages-shared/guardrails/RuleConfig/RuleConfig";
import GuardrailPolicyCodeSection from "@/v2/pages-shared/guardrails/GuardrailPolicyPanel/GuardrailPolicyCodeSection";
import useGuardrailPolicyForm, {
  GuardrailPolicyPanelMode,
  POLICY_NAME_MAX_LENGTH,
} from "@/v2/pages-shared/guardrails/GuardrailPolicyPanel/useGuardrailPolicyForm";
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
    name,
    setName,
    nameError,
    setNameError,
    description,
    setDescription,
    executionScope,
    setExecutionScope,
    rules,
    updateRule,
    toggleRule,
    ruleErrors,
    rulesError,
    isRenamed,
    isEdit,
    isDirty,
    isSubmitting,
    submitHandler,
  } = useGuardrailPolicyForm({ mode, policy, takenNames, open, setOpen });

  const handleClose = useCallback(() => setOpen(false), [setOpen]);

  const pii = rules[GuardrailTypes.PII];
  const topic = rules[GuardrailTypes.TOPIC];
  const promptInjection = rules[GuardrailTypes.PROMPT_INJECTION];

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
      <div className="flex size-full flex-col">
        <div className="flex-1 overflow-y-auto px-9 pb-6 pt-4">
          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="guardrailPolicyName">Name</Label>
              <Input
                id="guardrailPolicyName"
                dimension="sm"
                placeholder="Name your policy..."
                maxLength={POLICY_NAME_MAX_LENGTH}
                value={name}
                className={cn(
                  nameError &&
                    "!border-destructive focus-visible:!border-destructive",
                )}
                onChange={(event) => {
                  setName(event.target.value);
                  setNameError(undefined);
                }}
              />
              {nameError && <FormErrorSkeleton>{nameError}</FormErrorSkeleton>}
              {isRenamed && (
                <div className="comet-body-xs flex items-start gap-2 rounded-md bg-warning-box-bg p-2 text-warning-box-text">
                  <TriangleAlert className="mt-0.5 size-3.5 shrink-0 text-warning-box-icon-text" />
                  <span>
                    Applications reference a policy by name. Renaming this one
                    breaks any application still using &quot;{policy?.name}
                    &quot;.
                  </span>
                </div>
              )}
            </div>

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="guardrailPolicyDescription">
                Description{" "}
                <span className="font-normal text-foreground">(optional)</span>
              </Label>
              <Textarea
                id="guardrailPolicyDescription"
                placeholder="What this policy protects against"
                className="min-h-16 text-sm"
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                maxLength={1000}
              />
            </div>

            <div className="flex flex-col gap-1.5">
              <Label>Execution scope</Label>
              {/* Radios rather than a segmented control: each scope carries an explanation, and
                  both explanations being visible at once is what makes the choice obvious. */}
              <RadioGroup
                value={executionScope}
                onValueChange={(value) =>
                  setExecutionScope(value as GUARDRAIL_EXECUTION_SCOPE)
                }
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
            </div>
          </div>

          <Separator className="my-4" />

          {/* A heading, not a Label: a rule's own title is a Label, so the section needs to
              outrank it. */}
          <h2 className="comet-title-xs">Rules</h2>
          <Description className="mt-1 block">
            Switch on the checks this policy runs. A policy holds at most one
            rule of each type.
          </Description>

          <div className="mt-2 divide-y">
            <RuleConfig
              type={GuardrailTypes.PII}
              enabled={pii.enabled}
              toggleEnabled={() => toggleRule(GuardrailTypes.PII)}
            >
              <RuleConfig.PIIEntities
                value={pii.entities}
                error={ruleErrors["pii.entities"]}
                onChange={(entities) =>
                  updateRule(GuardrailTypes.PII, { entities })
                }
              />
            </RuleConfig>

            <RuleConfig
              type={GuardrailTypes.TOPIC}
              enabled={topic.enabled}
              toggleEnabled={() => toggleRule(GuardrailTypes.TOPIC)}
            >
              <RuleConfig.TopicsList
                id="topic-restricted"
                label="Restricted topics"
                hint="The check fails when the text is about any of these."
                value={topic.restrictedTopics}
                error={ruleErrors["topic.topics"]}
                onChange={(restrictedTopics) =>
                  updateRule(GuardrailTypes.TOPIC, { restrictedTopics })
                }
              />
              <RuleConfig.TopicsList
                id="topic-allowed"
                label="Allowed topics"
                hint="The check fails when the text is about none of these."
                value={topic.allowedTopics}
                onChange={(allowedTopics) =>
                  updateRule(GuardrailTypes.TOPIC, { allowedTopics })
                }
              />
            </RuleConfig>

            <RuleConfig
              type={GuardrailTypes.PROMPT_INJECTION}
              enabled={promptInjection.enabled}
              toggleEnabled={() => toggleRule(GuardrailTypes.PROMPT_INJECTION)}
            />
          </div>

          <div className="mt-4">
            <GuardrailPolicyCodeSection policyName={name} />
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
              variant="outline"
              onClick={handleClose}
              disabled={isSubmitting}
            >
              Cancel
            </Button>
            <Button disabled={isSubmitting || !isDirty} onClick={submitHandler}>
              {isSubmitting && <Spinner size="small" className="mr-2" />}
              {isEdit ? "Save changes" : "Create policy"}
            </Button>
          </div>
        </div>
      </div>
    </ResizableSidePanel>
  );
};

export default GuardrailPolicyPanel;
