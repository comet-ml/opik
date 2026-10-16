import React, { useCallback } from "react";

import { Form } from "@/ui/form";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/ui/resizable";
import {
  EVALUATORS_RULE_SCOPE,
  EvaluatorsRule,
  UI_EVALUATORS_RULE_TYPE,
} from "@/types/automations";
import useRuleCreateMutation from "@/api/automations/useRuleCreateMutation";
import useRuleUpdateMutation from "@/api/automations/useRuleUpdateMutation";
import useRuleEditorForm from "@/v2/pages-shared/automations/RuleEditorPanel/useRuleEditorForm";
import useRuleCreatedToast from "@/v2/pages-shared/automations/RuleEditorPanel/useRuleCreatedToast";
import RuleEditorFooter from "@/v2/pages-shared/automations/RuleEditorPanel/RuleEditorFooter";
import RuleNameField from "@/v2/pages-shared/automations/RuleEditorPanel/RuleNameField";
import JudgePromptField from "@/v2/pages-shared/automations/RuleEditorPanel/JudgePromptField";
import RuleScopeField from "@/v2/pages-shared/automations/RuleEditorPanel/RuleScopeField";
import FilteringSamplingCard from "@/v2/pages-shared/automations/RuleEditorPanel/FilteringSamplingCard";
import ScoreConfigurationCard from "@/v2/pages-shared/automations/RuleEditorPanel/ScoreConfigurationCard";
import CostLimitField from "@/v2/pages-shared/automations/RuleEditorPanel/CostLimitField";
import DecisionModelPromptField from "@/v2/pages-shared/automations/RuleEditorPanel/DecisionModelPromptField";
import DecisionModelQuestionsCard from "@/v2/pages-shared/automations/RuleEditorPanel/DecisionModelQuestionsCard";
import CodeEvaluatorField from "@/v2/pages-shared/automations/RuleEditorPanel/CodeEvaluatorField";
import { RuleDefaultModel } from "@/v2/pages-shared/automations/RuleEditorPanel/useRuleDefaultModel";

type RuleEditorFormProps = {
  onClose: () => void;
  projectId: string;
  ruleType: UI_EVALUATORS_RULE_TYPE;
  rule?: EvaluatorsRule;
  isClone: boolean;
  isEdit: boolean;
  defaultModel: RuleDefaultModel;
  defaultScope?: EVALUATORS_RULE_SCOPE;
  onRuleCreated?: (rule: EvaluatorsRule) => void;
};

const RuleEditorForm: React.FC<RuleEditorFormProps> = ({
  onClose,
  projectId,
  ruleType,
  rule,
  isClone,
  isEdit,
  defaultModel,
  defaultScope,
  onRuleCreated,
}) => {
  const {
    form,
    changeScope,
    applyTemplate,
    prepareForSubmit,
    buildRule,
    convertCodeSignature,
    promptMergeInfo,
  } = useRuleEditorForm({
    ruleType,
    projectId,
    rule,
    isClone,
    defaultModel,
    defaultScope,
  });
  const { mutate: createRule, isPending: isCreatePending } =
    useRuleCreateMutation();
  const { mutate: updateRule, isPending: isUpdatePending } =
    useRuleUpdateMutation();
  const showRuleCreatedToast = useRuleCreatedToast();

  const handleSubmit = useCallback(async () => {
    prepareForSubmit();
    await form.handleSubmit(() => {
      const { scope, triggerScope, projectIds } = form.getValues();
      if (rule && isEdit) {
        updateRule(
          { ruleId: rule.id, rule: buildRule() },
          {
            onSuccess: () => {
              showRuleCreatedToast(scope, triggerScope, projectIds);
              onClose();
            },
          },
        );
        return;
      }
      createRule(
        { rule: buildRule() },
        {
          onSuccess: (rule: EvaluatorsRule) => {
            showRuleCreatedToast(scope, triggerScope, projectIds);
            onRuleCreated?.(rule);
            onClose();
          },
        },
      );
    })();
  }, [
    rule,
    isEdit,
    form,
    prepareForSubmit,
    buildRule,
    createRule,
    updateRule,
    showRuleCreatedToast,
    onRuleCreated,
    onClose,
  ]);

  const isLLMJudge = ruleType === UI_EVALUATORS_RULE_TYPE.llm_judge;
  const isDecisionModelRule =
    ruleType === UI_EVALUATORS_RULE_TYPE.decision_model;
  const isSpanScope = form.watch("scope") === EVALUATORS_RULE_SCOPE.span;

  return (
    <Form {...form}>
      <div className="flex size-full flex-col">
        <div className="min-h-0 flex-1 px-5">
          <ResizablePanelGroup
            direction="horizontal"
            autoSaveId="online-evaluation-rule-editor-columns"
          >
            <ResizablePanel id="rule-editor-main" defaultSize={55} minSize={30}>
              <div className="h-full overflow-y-auto">
                <div className="flex min-h-full flex-col gap-4 pb-3 pt-5">
                  <RuleNameField form={form} />
                  {isLLMJudge && (
                    <JudgePromptField
                      form={form}
                      onSelectTemplate={applyTemplate}
                      mergeInfo={promptMergeInfo}
                    />
                  )}
                  {isDecisionModelRule && (
                    <DecisionModelPromptField form={form} />
                  )}
                  {ruleType === UI_EVALUATORS_RULE_TYPE.python_code && (
                    <CodeEvaluatorField
                      form={form}
                      onConvertSignature={convertCodeSignature}
                    />
                  )}
                </div>
              </div>
            </ResizablePanel>
            <ResizableHandle className="w-6 bg-transparent before:absolute before:inset-y-0 before:left-1/2 before:w-px data-[resize-handle-state=drag]:before:bg-primary data-[resize-handle-state=hover]:before:bg-primary" />
            <ResizablePanel
              id="rule-editor-settings"
              defaultSize={45}
              minSize={30}
            >
              <div className="h-full overflow-y-auto">
                <div className="flex flex-col gap-4 pb-7 pt-5">
                  <RuleScopeField
                    form={form}
                    onScopeChange={changeScope}
                    disabled={isEdit}
                  />
                  <FilteringSamplingCard form={form} projectId={projectId} />
                  {isLLMJudge && <ScoreConfigurationCard form={form} />}
                  {isDecisionModelRule && (
                    <DecisionModelQuestionsCard form={form} />
                  )}
                  {isLLMJudge && !isSpanScope && <CostLimitField form={form} />}
                </div>
              </div>
            </ResizablePanel>
          </ResizablePanelGroup>
        </div>
        <RuleEditorFooter
          form={form}
          isEdit={isEdit}
          onCancel={onClose}
          onSubmit={handleSubmit}
          isSubmitting={isCreatePending || isUpdatePending}
          hasPendingChanges={Boolean(promptMergeInfo)}
        />
      </div>
    </Form>
  );
};

export default RuleEditorForm;
