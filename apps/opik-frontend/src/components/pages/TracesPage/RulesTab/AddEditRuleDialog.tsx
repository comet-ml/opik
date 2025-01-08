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
import { EVALUATORS_RULE_TYPE, EvaluatorsRule } from "@/types/automations";
import useRuleCreateMutation from "@/api/automations/useRuleCreateMutation";
import useRuleUpdateMutation from "@/api/automations/useRuleUpdateMutation";
import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";

// TODO lala move and refactor
import PromptModelSelect from "@/components/pages/PlaygroundPage/PlaygroundPrompts/PromptModelSelect/PromptModelSelect";
import PromptModelConfigs from "@/components/pages/PlaygroundPage/PlaygroundPrompts/PromptModelSettings/PromptModelConfigs";
import { getModelProvider } from "@/lib/playground";
import get from "lodash/get";
import useAppStore from "@/store/AppStore";
import LLMJudgeScores from "@/components/pages/LLMShared/LLMJudgeScores/LLMJudgeScores";

const DEFAULT_SAMPLING_RATE = 0.3;

const DEBUG_EXAMPLE = {
  name: "Hello world",
  samplingRate: 0.25,
  type: "llm_as_judge",
  code: {
    model: {
      name: "gpt-4o",
      temperature: 0.3,
    },
    messages: [
      {
        role: "USER",
        content:
          "Summary: {{summary}}\nInstruction: {{instruction}}\n\nEvaluate the summary based on the following criteria:\n1. Relevance (1-5): How well does the summary address the given instruction?\n2. Conciseness (1-5): How concise is the summary while retaining key information?\n3. Technical Accuracy (1-5): How accurately does the summary convey technical details?",
      },
    ],
    variables: {
      summary: "input.questions.question1",
      instruction: "output.output",
    },
    schema: [
      {
        name: "Relevance",
        type: "INTEGER",
        description: "Relevance of the summary",
      },
      {
        name: "Conciseness",
        type: "DOUBLE",
        description: "Conciseness of the summary",
      },
      {
        name: "Technical Accuracy",
        type: "BOOLEAN",
        description: "Technical accuracy of the summary",
      },
    ],
  },
} as Partial<EvaluatorsRule>;

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
  const [model, setModel] = useState(
    get(defaultRule, ["code", "model", "name"]) || "",
  );
  const provider = model ? getModelProvider(model) : "";

  const [configs, setConfigs] = useState({
    temperature: get(defaultRule, ["code", "model", "temperature"]) || 0.3, // TODO lala
  });

  const [schemas, setSchemas] = useState(
    get(DEBUG_EXAMPLE, ["code", "schema"]) || [],
  ); // TODO lala

  const { mutate: createMutate } = useRuleCreateMutation();
  const { mutate: updateMutate } = useRuleUpdateMutation();

  const isLLMJudge = type === EVALUATORS_RULE_TYPE.llm_judge;
  const isEdit = Boolean(defaultRule);
  const title = isEdit ? "Edit rule" : "Create a new rule";
  const submitText = isEdit ? "Update rule" : "Create rule";

  const createPrompt = useCallback(() => {
    createMutate({
      projectId,
      rule: DEBUG_EXAMPLE,
    });
    setOpen(false);
  }, [createMutate, projectId, setOpen]);

  const editPrompt = useCallback(() => {
    updateMutate({
      projectId,
      rule: DEBUG_EXAMPLE,
    });
    setOpen(false);
  }, [updateMutate, projectId, setOpen]);

  const renderLLMJudgeType = () => {
    // TODO lala PromptModelSelect size
    // TODO lala PromptModelConfigs refactor
    return (
      <>
        <div className="flex flex-col gap-2 py-4">
          <Label htmlFor="name">Model</Label>
          <div className="flex h-10 items-center justify-center gap-2">
            <PromptModelSelect
              value={model}
              onChange={setModel}
              provider={provider}
              workspaceName={workspaceName}
            />
            <PromptModelConfigs
              provider={provider}
              configs={configs as never}
              onChange={setConfigs as never}
            />
          </div>
        </div>
        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="name">Prompt</Label>
          <div className="flex h-10 items-center justify-center gap-2">
            PROMPT SELECT WITH MESSAGES
          </div>
        </div>

        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="name">Score</Label>
          <LLMJudgeScores scores={schemas} onChange={setSchemas} />
        </div>
      </>
    );
  };

  const renderPythonCodeType = () => {
    return <div>TO BE IMPLEMENTED</div>;
  };

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
          {isLLMJudge ? renderLLMJudgeType() : renderPythonCodeType()}
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
