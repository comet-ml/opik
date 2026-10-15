import React, { useCallback } from "react";
import { ArrowUpRight } from "lucide-react";

import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import ResizableSidePanelTopBar from "@/shared/ResizableSidePanel/ResizableSidePanelTopBar";
import Loader from "@/shared/Loader/Loader";
import { Button } from "@/ui/button";
import { EvaluatorsRule, UI_EVALUATORS_RULE_TYPE } from "@/types/automations";
import { buildDocsUrl } from "@/v2/lib/utils";
import RuleEditorForm from "@/v2/pages-shared/automations/RuleEditorPanel/RuleEditorForm";
import useRuleDefaultModel from "@/v2/pages-shared/automations/RuleEditorPanel/useRuleDefaultModel";
import { getRuleUIType } from "@/v2/pages-shared/automations/RuleEditorPanel/helpers";

const RULES_DOCS_LINK = buildDocsUrl("/production/online-evaluation/rules");

const TITLE_BY_RULE_TYPE: Record<UI_EVALUATORS_RULE_TYPE, string> = {
  [UI_EVALUATORS_RULE_TYPE.llm_judge]: "New LLM-as-a-Judge",
  [UI_EVALUATORS_RULE_TYPE.python_code]: "New code evaluator",
  [UI_EVALUATORS_RULE_TYPE.decision_model]: "New decision model evaluator",
};

type RuleEditorPanelProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  projectId?: string;
  ruleType?: UI_EVALUATORS_RULE_TYPE;
  rule?: EvaluatorsRule;
  isClone?: boolean;
  onRuleCreated?: (rule: EvaluatorsRule) => void;
};

const RuleEditorPanel: React.FC<RuleEditorPanelProps> = ({
  open,
  setOpen,
  projectId = "",
  ruleType: newRuleType = UI_EVALUATORS_RULE_TYPE.llm_judge,
  rule,
  isClone = false,
  onRuleCreated,
}) => {
  const isEdit = Boolean(rule) && !isClone;
  const ruleType = rule ? getRuleUIType(rule) : newRuleType;
  const { isReady, defaultModel } = useRuleDefaultModel();
  const handleClose = useCallback(() => setOpen(false), [setOpen]);

  return (
    <ResizableSidePanel
      panelId="online-evaluation-rule-editor"
      entity="rule"
      open={open}
      onClose={handleClose}
      minWidth={700}
      header={
        <ResizableSidePanelTopBar
          variant="form"
          title={rule && isEdit ? rule.name : TITLE_BY_RULE_TYPE[ruleType]}
          onClose={handleClose}
        >
          <Button
            variant="outline"
            size="2xs"
            className="rounded-[4px]"
            asChild
          >
            <a href={RULES_DOCS_LINK} target="_blank" rel="noreferrer">
              Docs
              <ArrowUpRight className="ml-1 size-3" />
            </a>
          </Button>
        </ResizableSidePanelTopBar>
      }
    >
      {isReady || ruleType !== UI_EVALUATORS_RULE_TYPE.llm_judge ? (
        <RuleEditorForm
          onClose={handleClose}
          projectId={projectId}
          ruleType={ruleType}
          rule={rule}
          isClone={isClone}
          isEdit={isEdit}
          defaultModel={defaultModel}
          onRuleCreated={onRuleCreated}
        />
      ) : (
        <Loader />
      )}
    </ResizableSidePanel>
  );
};

export default RuleEditorPanel;
