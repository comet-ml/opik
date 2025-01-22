import React, { useCallback, useMemo, useState } from "react";
import uniq from "lodash/uniq";

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
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
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
import { useBooleanTimeoutState } from "@/hooks/useBooleanTimeoutState";

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
    defaultRule?.sampling_rate ?? DEFAULT_SAMPLING_RATE,
  );
  const [type] = useState(defaultRule?.type || EVALUATORS_RULE_TYPE.llm_judge);
  const isLLMJudge = type === EVALUATORS_RULE_TYPE.llm_judge;

  const [llmJudgeDetails, setLLMJudgeDetails] = useState(
    isLLMJudge && defaultRule
      ? convertLLMJudgeObjectToLLMJudgeData(defaultRule.code as LLMJudgeObject)
      : cloneDeep(DEFAULT_LLM_AS_JUDGE_DATA),
  );

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

  const rule = useMemo(() => {
    return {
      name,
      project_id: projectId,
      sampling_rate: samplingRate,
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

  const [showValidation, setShowValidation] = useBooleanTimeoutState({
    timeout: 10000,
  });
  const [validationMessage, setValidationMessage] = useState("");
  const validate = useCallback(() => {
    const messages: string[] = [];
    if (rule.name === "") {
      messages.push("Rule name is required");
    }

    if (isLLMJudge) {
      const code = rule.code as LLMJudgeObject;

      if ((code.model.name as never) === "") {
        messages.push("Model is required");
      }

      code.messages.forEach((m, index) => {
        if (m.content === "") {
          messages.push(`Prompt message #${index} can not be empty.`);
        }
      });

      Object.entries(code.variables).forEach(([k, v]) => {
        if (v === "" || !/^(input|output|metadata)/.test(v)) {
          messages.push(
            v === ""
              ? `Mapping for variable "${k}" is required`
              : `Mapping for variable "${k}" is invalid, it should begin with "input", "output", or "metadata" and follow this format: "input.[PATH]" For example: "input.message"`,
          );
        }
      });

      const schemaNames = code.schema.map((s) => s.name);

      schemaNames.forEach((s, index) => {
        if (s === "") {
          messages.push(`Score definition #${index} name can not be empty.`);
        }
      });

      if (schemaNames.length !== uniq(schemaNames).length) {
        messages.push("All score definition names should be unique");
      }
    }

    setValidationMessage(messages.map((m) => `- ${m}`).join("\n"));

    if (messages.length) {
      setShowValidation(true);
    }

    return messages.length === 0;
  }, [rule.name, rule.code, isLLMJudge, setShowValidation]);

  const createPrompt = useCallback(() => {
    if (!validate()) return;

    createMutate({
      projectId,
      rule,
    });
    setOpen(false);
  }, [createMutate, rule, validate, projectId, setOpen]);

  const editPrompt = useCallback(() => {
    if (!validate()) return;
    updateMutate({
      ruleId: defaultRule!.id,
      projectId,
      rule,
    });
    setOpen(false);
  }, [updateMutate, rule, validate, defaultRule, projectId, setOpen]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[790px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          {showValidation && (
            <div
              className="absolute bottom-24 right-7 z-10 cursor-pointer rounded bg-white shadow-xl"
              onClick={() => setShowValidation(false)}
            >
              <Alert variant="destructive">
                <AlertTitle>Validation errors:</AlertTitle>
                <AlertDescription className="min-w-72 max-w-[500px] whitespace-pre-wrap">
                  {validationMessage}
                </AlertDescription>
              </Alert>
            </div>
          )}
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="ruleName">Name</Label>
            <Input
              id="ruleName"
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
            id="sampling_rate"
            label="Samping rate"
            tooltip="Percentage of traces to evaluate"
          />
          {isLLMJudge ? (
            <LLMJudgeRuleDetails
              data={llmJudgeDetails}
              workspaceName={workspaceName}
              onChange={setLLMJudgeDetails}
              projectId={projectId}
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
