import React, { useCallback, useEffect, useMemo } from "react";
import cloneDeep from "lodash/cloneDeep";
import get from "lodash/get";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm, UseFormReturn } from "react-hook-form";
import { ExternalLink, MessageCircleWarning } from "lucide-react";
import { useNavigate } from "@tanstack/react-router";

import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";
import { SheetTopBar } from "@/ui/sheet";
import { Label } from "@/ui/label";
import { Form, FormControl, FormField, FormItem, FormMessage } from "@/ui/form";
import { Input } from "@/ui/input";
import { Switch } from "@/ui/switch";
import { ToastAction } from "@/ui/toast";
import { useToast } from "@/ui/use-toast";
import SideDialog from "@/shared/SideDialog/SideDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import ExplainerCallout from "@/shared/ExplainerCallout/ExplainerCallout";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import { useConfirmAction } from "@/shared/ConfirmDialog/useConfirmAction";
import {
  EVAL_TRIGGER_SCOPE,
  EVALUATORS_RULE_SCOPE,
  EVALUATORS_RULE_TYPE,
  EvaluatorsRule,
  LLMJudgeObject,
  PythonCodeObject,
  UI_EVALUATORS_RULE_TYPE,
} from "@/types/automations";
import { Filter } from "@/types/filters";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { LLM_JUDGE, LLM_MESSAGE_ROLE, LLMJudgeSchema } from "@/types/llm";
import { isFilterValid } from "@/lib/filters";
import { isPythonCodeRule, isLLMJudgeRule } from "@/lib/rules";
import useAppStore from "@/store/AppStore";
import useRuleCreateMutation from "@/api/automations/useRuleCreateMutation";
import useRuleUpdateMutation from "@/api/automations/useRuleUpdateMutation";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { usePermissions } from "@/contexts/PermissionsContext";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";
import { buildDocsUrl } from "@/v2/lib/utils";
import { LOGS_TYPE } from "@/constants/traces";
import {
  DEFAULT_PYTHON_CODE_THREAD_DATA,
  DEFAULT_PYTHON_CODE_TRACE_DATA,
  DEFAULT_PYTHON_CODE_SPAN_DATA,
  LLM_PROMPT_CUSTOM_THREAD_TEMPLATE,
  LLM_PROMPT_CUSTOM_TRACE_TEMPLATE,
  LLM_PROMPT_CUSTOM_SPAN_TEMPLATE,
} from "@/constants/llm";
import PythonCodeRuleDetails from "@/v2/pages-shared/automations/AddEditRuleDialog/PythonCodeRuleDetails";
import LLMJudgeRuleDetails from "@/v2/pages-shared/automations/AddEditRuleDialog/LLMJudgeRuleDetails";
import LLMJudgeMaxCostField from "@/v2/pages-shared/automations/AddEditRuleDialog/LLMJudgeMaxCostField";
import RuleScopeSection from "@/v2/pages-shared/automations/AddEditRuleDialog/RuleScopeSection";
import LLMJudgeScores from "@/v2/pages-shared/llm/LLMJudgeScores/LLMJudgeScores";
import RuleFilteringSection, {
  TRACE_FILTER_COLUMNS,
  THREAD_FILTER_COLUMNS,
  SPAN_FILTER_COLUMNS,
} from "@/v2/pages-shared/automations/AddEditRuleDialog/RuleFilteringSection";
import {
  convertLLMJudgeDataToLLMJudgeObject,
  convertLLMJudgeObjectToLLMJudgeData,
  EvaluationRuleFormSchema,
  EvaluationRuleFormType,
} from "@/v2/pages-shared/automations/AddEditRuleDialog/schema";
import {
  getBackendRuleType,
  getUIRuleScope,
  getUIRuleType,
  normalizeFilters,
} from "./helpers";

// A new rule starts from a blank prompt; the templates are offered beside it.
const EMPTY_JUDGE_MESSAGES = [
  { id: "kYZIEMPT", role: LLM_MESSAGE_ROLE.user, content: "" },
];

const judgeDefaults = (schema: LLMJudgeSchema[]) => ({
  model: "",
  config: {
    temperature: 0.0,
    seed: null,
    custom_parameters: null,
  },
  template: LLM_JUDGE.custom,
  messages: EMPTY_JUDGE_MESSAGES,
  variables: {},
  schema,
  maxCostUsd: null,
});

export const DEFAULT_LLM_AS_JUDGE_DATA = {
  [EVALUATORS_RULE_SCOPE.trace]: judgeDefaults(
    LLM_PROMPT_CUSTOM_TRACE_TEMPLATE.schema,
  ),
  [EVALUATORS_RULE_SCOPE.thread]: judgeDefaults(
    LLM_PROMPT_CUSTOM_THREAD_TEMPLATE.schema,
  ),
  [EVALUATORS_RULE_SCOPE.span]: judgeDefaults(
    LLM_PROMPT_CUSTOM_SPAN_TEMPLATE.schema,
  ),
};

const DEFAULT_PYTHON_CODE_DATA: Record<
  EVALUATORS_RULE_SCOPE,
  PythonCodeObject
> = {
  [EVALUATORS_RULE_SCOPE.trace]: DEFAULT_PYTHON_CODE_TRACE_DATA,
  [EVALUATORS_RULE_SCOPE.thread]: DEFAULT_PYTHON_CODE_THREAD_DATA,
  [EVALUATORS_RULE_SCOPE.span]: DEFAULT_PYTHON_CODE_SPAN_DATA,
};

const filterColumnsForScope = (scope: EVALUATORS_RULE_SCOPE) =>
  (scope === EVALUATORS_RULE_SCOPE.thread
    ? THREAD_FILTER_COLUMNS
    : scope === EVALUATORS_RULE_SCOPE.span
      ? SPAN_FILTER_COLUMNS
      : TRACE_FILTER_COLUMNS) as ColumnData<unknown>[];

type AddEditRuleDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  projectId: string;
  rule?: EvaluatorsRule;
  /** Rule type for a new rule; picked by the caller (create menu) before the panel opens. */
  uiType?: UI_EVALUATORS_RULE_TYPE;
  datasetColumnNames?: string[]; // Optional: dataset column names from playground
  hideScopeSelector?: boolean; // Optional: hide scope selector (e.g., for contexts that only support one scope)
  defaultScope?: EVALUATORS_RULE_SCOPE; // Optional: default scope for new rules
  mode?: "create" | "edit" | "clone"; // Optional: dialog mode
  onRuleCreated?: (rule: EvaluatorsRule) => void; // Optional: fired with the created rule
};

const AddEditRuleDialog: React.FC<AddEditRuleDialogProps> = ({
  open: initialOpen,
  setOpen,
  projectId,
  rule: defaultRule,
  uiType = UI_EVALUATORS_RULE_TYPE.llm_judge,
  datasetColumnNames,
  hideScopeSelector = false,
  defaultScope,
  mode,
  onRuleCreated,
}) => {
  const {
    permissions: { canUpdateOnlineEvaluationRules },
  } = usePermissions();

  const open = initialOpen && canUpdateOnlineEvaluationRules;

  const isCodeMetricEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.PYTHON_EVALUATOR_ENABLED,
  );
  const isSpanLlmAsJudgeEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.SPAN_LLM_AS_JUDGE_ENABLED,
  );
  const isSpanPythonCodeEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.SPAN_USER_DEFINED_METRIC_PYTHON_ENABLED,
  );
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const { isOpen, setIsOpen, requestConfirm, confirm, cancel } =
    useConfirmAction();
  const { toast } = useToast();

  const formUIRuleType = defaultRule?.type
    ? getUIRuleType(defaultRule.type)
    : uiType;
  const formScope = defaultRule?.type
    ? getUIRuleScope(defaultRule.type)
    : defaultScope || EVALUATORS_RULE_SCOPE.trace;

  const getInitialRuleName = () => {
    if (mode === "clone" && defaultRule) {
      return `${defaultRule.name} (Copy)`;
    }
    return defaultRule?.name || "";
  };

  const form: UseFormReturn<EvaluationRuleFormType> = useForm<
    z.infer<typeof EvaluationRuleFormSchema>
  >({
    resolver: zodResolver(EvaluationRuleFormSchema),
    defaultValues: {
      ruleName: getInitialRuleName(),
      projectIds:
        defaultRule?.projects?.map((p) => p.project_id) ||
        (projectId ? [projectId] : []),
      samplingRate: defaultRule?.sampling_rate ?? 1,
      uiType: formUIRuleType,
      scope: formScope,
      type: getBackendRuleType(formScope, formUIRuleType),
      enabled: defaultRule?.enabled ?? true,
      triggerScope: defaultRule?.trigger_scope ?? EVAL_TRIGGER_SCOPE.production,
      filters: normalizeFilters(
        defaultRule?.filters ?? [],
        filterColumnsForScope(formScope),
      ) as Filter[],
      pythonCodeDetails:
        defaultRule && isPythonCodeRule(defaultRule)
          ? (defaultRule.code as PythonCodeObject)
          : cloneDeep(DEFAULT_PYTHON_CODE_DATA[formScope]),
      llmJudgeDetails:
        defaultRule && isLLMJudgeRule(defaultRule)
          ? convertLLMJudgeObjectToLLMJudgeData(
              defaultRule.code as LLMJudgeObject,
            )
          : cloneDeep(DEFAULT_LLM_AS_JUDGE_DATA[formScope]),
    },
  });

  const isLLMJudge = form.watch("uiType") === UI_EVALUATORS_RULE_TYPE.llm_judge;
  const scope = form.watch("scope");
  const isThreadScope = scope === EVALUATORS_RULE_SCOPE.thread;
  const isSpanScope = scope === EVALUATORS_RULE_SCOPE.span;

  const formProjectIds = form.watch("projectIds");

  // Reset form to default values when dialog opens for creating a new rule
  useEffect(() => {
    if (open && !defaultRule) {
      const initialScope = defaultScope || EVALUATORS_RULE_SCOPE.trace;

      form.reset({
        ruleName: "",
        projectIds: projectId ? [projectId] : [],
        samplingRate: 1,
        uiType,
        scope: initialScope,
        type: getBackendRuleType(initialScope, uiType),
        enabled: true,
        triggerScope: EVAL_TRIGGER_SCOPE.production,
        filters: [],
        llmJudgeDetails: cloneDeep(DEFAULT_LLM_AS_JUDGE_DATA[initialScope]),
        pythonCodeDetails: cloneDeep(DEFAULT_PYTHON_CODE_DATA[initialScope]),
      } as EvaluationRuleFormType);
    } else if (open && defaultRule && mode === "clone") {
      // For clone mode, reset the form with cloned rule data and append " (Copy)" to name
      const cloneFormData = {
        ruleName: `${defaultRule.name} (Copy)`,
        projectIds: projectId ? [projectId] : [],
        samplingRate: defaultRule.sampling_rate ?? 1,
        uiType: formUIRuleType,
        scope: formScope,
        type: getBackendRuleType(formScope, formUIRuleType),
        enabled: defaultRule.enabled ?? true,
        triggerScope:
          defaultRule.trigger_scope ?? EVAL_TRIGGER_SCOPE.production,
        filters: normalizeFilters(
          defaultRule.filters ?? [],
          filterColumnsForScope(formScope),
        ) as Filter[],
        pythonCodeDetails:
          defaultRule && isPythonCodeRule(defaultRule)
            ? (defaultRule.code as PythonCodeObject)
            : cloneDeep(DEFAULT_PYTHON_CODE_DATA[formScope]),
        llmJudgeDetails:
          defaultRule && isLLMJudgeRule(defaultRule)
            ? convertLLMJudgeObjectToLLMJudgeData(
                defaultRule.code as LLMJudgeObject,
              )
            : cloneDeep(DEFAULT_LLM_AS_JUDGE_DATA[formScope]),
      };
      form.reset(cloneFormData as EvaluationRuleFormType);
    }
  }, [
    open,
    defaultRule,
    projectId,
    defaultScope,
    mode,
    uiType,
    formScope,
    formUIRuleType,
    form,
  ]);

  // The main score is named after the rule, so the score card needs no name
  // field. Older rules whose first score was named separately keep that name
  // and show the field; a clone keeps the original score name as well.
  const isMainScoreLinked = useMemo(
    () =>
      mode !== "clone" &&
      (!defaultRule ||
        (isLLMJudgeRule(defaultRule) &&
          (defaultRule.code as LLMJudgeObject).schema?.[0]?.name ===
            defaultRule.name)),
    [defaultRule, mode],
  );
  const ruleName = form.watch("ruleName");
  const mainScoreName = form.watch("llmJudgeDetails.schema")?.[0]?.name;
  useEffect(() => {
    if (!isMainScoreLinked || !isLLMJudge || mainScoreName === ruleName) {
      return;
    }
    const schema = form.getValues("llmJudgeDetails.schema");
    if (schema?.[0]) {
      form.setValue("llmJudgeDetails.schema", [
        { ...schema[0], name: ruleName },
        ...schema.slice(1),
      ]);
    }
  }, [ruleName, mainScoreName, isMainScoreLinked, isLLMJudge, form]);

  const handleScopeChange = useCallback(
    (value: EVALUATORS_RULE_SCOPE) => {
      const applyChange = () => {
        const { uiType: currentUIType } = form.getValues();
        const type = getBackendRuleType(value, currentUIType);

        form.setValue("scope", value);
        form.setValue("type", type);

        // Reset filters when scope changes as columns are different
        form.setValue("filters", []);

        form.setValue(
          "llmJudgeDetails",
          cloneDeep(DEFAULT_LLM_AS_JUDGE_DATA[value]),
        );
        form.setValue(
          "pythonCodeDetails",
          cloneDeep(DEFAULT_PYTHON_CODE_DATA[value]),
        );
      };

      if (
        Object.keys(form.formState.dirtyFields).some(
          (key) => key === "llmJudgeDetails" || key === "pythonCodeDetails",
        )
      ) {
        requestConfirm(applyChange);
      } else {
        applyChange();
      }
    },
    [form, requestConfirm],
  );

  const { mutate: createMutate } = useRuleCreateMutation();
  const { mutate: updateMutate } = useRuleUpdateMutation();

  const isEdit = mode === "edit";
  const isClone = mode === "clone";
  const typeLabel = isLLMJudge ? "LLM-as-judge" : "code metric";
  const title = isEdit
    ? `Edit ${typeLabel} rule`
    : isClone
      ? `Clone ${typeLabel} rule`
      : `New ${typeLabel} rule`;
  const submitText = isEdit ? "Update rule" : "Create rule";

  const isCodeMetricEditBlock = !isCodeMetricEnabled && !isLLMJudge && isEdit;
  const showSpanScope = isLLMJudge
    ? isSpanLlmAsJudgeEnabled
    : isSpanPythonCodeEnabled;

  const onRuleCreatedEdited = useCallback(() => {
    const expainerIdMap = {
      [EVALUATORS_RULE_SCOPE.trace]:
        EXPLAINER_ID.i_added_edited_a_new_online_evaluation_rule_now_what,
      [EVALUATORS_RULE_SCOPE.thread]:
        EXPLAINER_ID.i_added_edited_a_new_online_evaluation_thread_level_rule_now_what,
      [EVALUATORS_RULE_SCOPE.span]:
        EXPLAINER_ID.i_added_edited_a_new_online_evaluation_span_level_rule_now_what,
    };
    const explainer = EXPLAINERS_MAP[expainerIdMap[scope]];

    // Only show "Go to project" button if exactly one project is selected
    const actions =
      formProjectIds.length === 1
        ? [
            <ToastAction
              variant="link"
              size="sm"
              className="px-0"
              altText="Go to project"
              key="Go to project"
              onClick={() => {
                navigate({
                  to: "/$workspaceName/projects/$projectId/logs",
                  params: {
                    projectId: formProjectIds[0],
                    workspaceName,
                  },
                  search: {
                    logsType: {
                      [EVALUATORS_RULE_SCOPE.trace]: LOGS_TYPE.traces,
                      [EVALUATORS_RULE_SCOPE.thread]: LOGS_TYPE.threads,
                      [EVALUATORS_RULE_SCOPE.span]: LOGS_TYPE.spans,
                    }[scope],
                  },
                });
              }}
            >
              Go to project
            </ToastAction>,
          ]
        : undefined;

    toast({
      title: explainer.title,
      description: explainer.description,
      actions,
    });
  }, [navigate, toast, workspaceName, scope, formProjectIds]);

  const getRule = useCallback(() => {
    const formData = form.getValues();
    const ruleType = formData.type;

    const validFilters = formData.filters
      .filter((f) =>
        isFilterValid(
          (f.field === "input" || f.field === "output") && !f.key
            ? { ...f, type: COLUMN_TYPE.string }
            : f,
        ),
      )
      .map((f) => {
        if ((f.field === "input" || f.field === "output") && f.key) {
          return { ...f, field: `${f.field}_json` };
        }
        return f;
      });

    const ruleData = {
      name: formData.ruleName,
      project_ids: formData.projectIds,
      sampling_rate: formData.samplingRate,
      enabled: formData.enabled,
      trigger_scope: formData.triggerScope,
      filters: validFilters,
      type: ruleType,
    };

    if (ruleType === EVALUATORS_RULE_TYPE.llm_judge) {
      return {
        ...ruleData,
        code: convertLLMJudgeDataToLLMJudgeObject(formData.llmJudgeDetails),
      } as EvaluatorsRule;
    }

    if (ruleType === EVALUATORS_RULE_TYPE.thread_llm_judge) {
      return {
        ...ruleData,
        code: {
          ...convertLLMJudgeDataToLLMJudgeObject(formData.llmJudgeDetails),
          variables: undefined,
        },
      } as EvaluatorsRule;
    }

    if (ruleType === EVALUATORS_RULE_TYPE.span_llm_judge) {
      return {
        ...ruleData,
        code: convertLLMJudgeDataToLLMJudgeObject(formData.llmJudgeDetails),
      } as EvaluatorsRule;
    }

    return {
      ...ruleData,
      code: formData.pythonCodeDetails,
    } as EvaluatorsRule;
  }, [form]);

  const createPrompt = useCallback(() => {
    createMutate(
      {
        rule: getRule(),
      },
      {
        onSuccess: (rule: EvaluatorsRule) => {
          onRuleCreatedEdited();
          onRuleCreated?.(rule);
        },
      },
    );
    setOpen(false);
  }, [createMutate, getRule, onRuleCreatedEdited, onRuleCreated, setOpen]);

  const editPrompt = useCallback(() => {
    updateMutate(
      {
        ruleId: defaultRule!.id,
        rule: getRule(),
      },
      { onSuccess: onRuleCreatedEdited },
    );
    setOpen(false);
  }, [updateMutate, defaultRule, getRule, onRuleCreatedEdited, setOpen]);

  const onSubmit = useCallback(
    () => (isEdit ? editPrompt() : createPrompt()),
    [isEdit, editPrompt, createPrompt],
  );

  return (
    <>
      <SideDialog
        open={open}
        setOpen={setOpen}
        blockOverlayClose
        header={
          <SheetTopBar title={title}>
            <Button variant="outline" size="sm" asChild>
              <a
                href={buildDocsUrl("/production/online-evaluation/rules")}
                target="_blank"
                rel="noreferrer"
              >
                Docs
                <ExternalLink className="ml-1.5 size-3.5" />
              </a>
            </Button>
          </SheetTopBar>
        }
      >
        <div
          className="flex h-[calc(100%-var(--header-height))] flex-col"
          data-testid="add-edit-rule-dialog"
        >
          <Form {...form}>
            <form
              className="flex min-h-0 flex-1 flex-col"
              onSubmit={form.handleSubmit(onSubmit)}
            >
              <div className="min-h-0 flex-1 overflow-y-auto px-6 py-4">
                {isEdit && (
                  <ExplainerCallout
                    Icon={MessageCircleWarning}
                    className="mb-4"
                    isDismissable={false}
                    {...EXPLAINERS_MAP[
                      isThreadScope
                        ? EXPLAINER_ID.what_happens_if_i_edit_a_thread_rule
                        : EXPLAINER_ID.what_happens_if_i_edit_a_rule
                    ]}
                  />
                )}
                <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_400px]">
                  <div className="flex min-w-0 flex-col gap-4">
                    <FormField
                      control={form.control}
                      name="ruleName"
                      render={({ field, formState }) => {
                        const validationErrors = get(formState.errors, [
                          "ruleName",
                        ]);
                        return (
                          <FormItem>
                            <Label>Name</Label>
                            <FormControl>
                              <Input
                                className={cn({
                                  "border-destructive": Boolean(
                                    validationErrors?.message,
                                  ),
                                })}
                                placeholder="Rule name"
                                {...field}
                              />
                            </FormControl>
                            <FormMessage />
                          </FormItem>
                        );
                      }}
                    />
                    {isLLMJudge ? (
                      <LLMJudgeRuleDetails
                        workspaceName={workspaceName}
                        projectId={formProjectIds[0] || ""}
                        form={form}
                        datasetColumnNames={datasetColumnNames}
                      />
                    ) : (
                      <PythonCodeRuleDetails
                        form={form}
                        projectId={formProjectIds[0] || ""}
                        datasetColumnNames={datasetColumnNames}
                      />
                    )}
                  </div>

                  <div className="flex min-w-0 flex-col gap-4">
                    {!hideScopeSelector && (
                      <RuleScopeSection
                        form={form}
                        onScopeChange={handleScopeChange}
                        showSpanScope={showSpanScope}
                        disabled={isEdit}
                      />
                    )}
                    <RuleFilteringSection
                      form={form}
                      projectId={formProjectIds[0] || ""}
                    />
                    {isLLMJudge && (
                      <FormField
                        control={form.control}
                        name="llmJudgeDetails.schema"
                        render={({ field, formState }) => (
                          <LLMJudgeScores
                            validationErrors={get(formState.errors, [
                              "llmJudgeDetails",
                              "schema",
                            ])}
                            scores={field.value}
                            onChange={field.onChange}
                            showMainScoreName={!isMainScoreLinked}
                          />
                        )}
                      />
                    )}
                    {/* A cost cap only applies where the judge may loop with tools — trace and thread scope. */}
                    {isLLMJudge && !isSpanScope && (
                      <LLMJudgeMaxCostField form={form} />
                    )}
                  </div>
                </div>
              </div>

              <div className="flex shrink-0 items-center justify-between gap-4 border-t border-border px-6 py-3">
                <FormField
                  control={form.control}
                  name="enabled"
                  render={({ field }) => (
                    <FormItem className="flex flex-row items-center gap-2 space-y-0 rounded-md border border-border px-3 py-1.5">
                      <FormControl>
                        <Switch
                          id="enabled"
                          size="sm"
                          aria-label="Enable rule"
                          checked={field.value}
                          onCheckedChange={field.onChange}
                        />
                      </FormControl>
                      <Label htmlFor="enabled" className="comet-body-s">
                        Enabled
                      </Label>
                    </FormItem>
                  )}
                />
                <div className="flex items-center gap-2">
                  <Button
                    variant="outline"
                    type="button"
                    onClick={() => setOpen(false)}
                  >
                    Cancel
                  </Button>
                  {isCodeMetricEditBlock ? (
                    <TooltipWrapper content="Code metric cannot be updated. This feature is not available for this environment">
                      <span>
                        <Button type="submit" disabled>
                          {submitText}
                        </Button>
                      </span>
                    </TooltipWrapper>
                  ) : (
                    <Button
                      type="submit"
                      data-testid="add-edit-rule-dialog-submit"
                    >
                      {submitText}
                    </Button>
                  )}
                </div>
              </div>
            </form>
          </Form>
        </div>
      </SideDialog>

      <ConfirmDialog
        open={isOpen}
        setOpen={setIsOpen}
        onCancel={cancel}
        onConfirm={confirm}
        title="You’re about to lose your changes"
        description={`If you change the evaluation scope, your current rule settings — including prompt, model, and variable mappings — will be reset.
Are you sure you want to continue?`}
        cancelText="Cancel"
        confirmText="Reset and continue"
      />
    </>
  );
};

export default AddEditRuleDialog;
