import React, { useCallback, useState } from "react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  EVALUATORS_RULE_TYPE,
  EvaluatorsRule,
  LLMJudgeObject,
  PythonCodeObject,
} from "@/types/automations";
import useAppStore from "@/store/AppStore";
import useRuleCreateMutation from "@/api/automations/useRuleCreateMutation";
import useRuleUpdateMutation from "@/api/automations/useRuleUpdateMutation";
import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import PythonCodeRuleDetails from "@/components/pages/TracesPage/RulesTab/AddEditRuleDialog/PythonCodeRuleDetails";
import LLMJudgeRuleDetails from "@/components/pages/TracesPage/RulesTab/AddEditRuleDialog/LLMJudgeRuleDetails";
import cloneDeep from "lodash/cloneDeep";
import {
  DEFAULT_LLM_AS_JUDGE_DATA,
  DEFAULT_SAMPLING_RATE,
} from "@/constants/automations";
import {
  convertLLMJudgeDataToLLMJudgeObject,
  convertLLMJudgeObjectToLLMJudgeData,
} from "@/lib/automations";

type AddEditRuleDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  projectId: string;
  rule?: EvaluatorsRule;
};

const AddEditRuleDialog: React.FC<AddEditRuleDialogProps> = ({
  open,
  setOpen,
  projectId,
  rule: defaultRule,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [name, setName] = useState(defaultRule?.name || "");
  const [samplingRate, setSamplingRate] = useState(
    defaultRule?.samplingRate || DEFAULT_SAMPLING_RATE,
  );
  const [type] = useState(defaultRule?.type || EVALUATORS_RULE_TYPE.llm_judge);
  const isLLMJudge = type === EVALUATORS_RULE_TYPE.llm_judge;

  // TODO lala verify
  const [llmJudgeDetails, setLLMJudgeDetails] = useState(
    isLLMJudge && defaultRule
      ? convertLLMJudgeObjectToLLMJudgeData(defaultRule.code as LLMJudgeObject)
      : cloneDeep(DEFAULT_LLM_AS_JUDGE_DATA),
  );

  // TODO lala verify
  const [pythonCodeDetails] = useState(
    !isLLMJudge && defaultRule
      ? (defaultRule.code as PythonCodeObject)
      : undefined,
  );

  const { mutate: createMutate } = useRuleCreateMutation();
  const { mutate: updateMutate } = useRuleUpdateMutation();

  const isEdit = Boolean(defaultRule);
  const title = isEdit ? "Edit rule" : "Create a new rule";
  const submitText = isEdit ? "Update rule" : "Create rule";

  const generateRule = useCallback(() => {
    return {
      name,
      projectId,
      samplingRate,
      type,
      code: isLLMJudge
        ? convertLLMJudgeDataToLLMJudgeObject(llmJudgeDetails)
        : pythonCodeDetails,
    } as EvaluatorsRule;
  }, [
    name,
    pythonCodeDetails,
    projectId,
    samplingRate,
    type,
    isLLMJudge,
    llmJudgeDetails,
  ]);

  const createPrompt = useCallback(() => {
    createMutate({
      projectId,
      rule: generateRule(),
    });
    setOpen(false);
  }, [createMutate, generateRule, projectId, setOpen]);

  const editPrompt = useCallback(() => {
    updateMutate({
      projectId,
      rule: generateRule(),
    });
    setOpen(false);
  }, [updateMutate, generateRule, projectId, setOpen]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[790px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="name">Name</Label>
            <Input
              id="name"
              placeholder="Rule name"
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
          </div>
          <SliderInputControl
            min={0}
            max={1}
            step={0.01}
            defaultValue={DEFAULT_SAMPLING_RATE}
            value={samplingRate}
            onChange={setSamplingRate}
            id="samplingRate"
            label="Samping rate"
            tooltip="Percentage of traces to evaluate"
          />
          {isLLMJudge ? (
            <LLMJudgeRuleDetails
              data={llmJudgeDetails}
              workspaceName={workspaceName}
              onChange={setLLMJudgeDetails}
            />
          ) : (
            <PythonCodeRuleDetails data={pythonCodeDetails} />
          )}
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button type="submit" onClick={isEdit ? editPrompt : createPrompt}>
            {submitText}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditRuleDialog;
