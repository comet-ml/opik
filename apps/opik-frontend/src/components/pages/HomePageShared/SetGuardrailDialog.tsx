import React, { useState } from "react";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import SideDialog from "@/components/shared/SideDialog/SideDialog";
import { SheetTitle } from "@/components/ui/sheet";
import ApiKeyCard from "@/components/pages-shared/onboarding/ApiKeyCard/ApiKeyCard";
import CreateExperimentCode from "@/components/pages-shared/onboarding/CreateExperimentCode/CreateExperimentCode";
import GuardrailConfig from "./GuardrailConfig";
import { Separator } from "@/components/ui/separator";

type SetGuardrailDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
};

const SetGuardrailDialog: React.FC<SetGuardrailDialogProps> = ({
  open,
  setOpen,
}) => {
  const [topicThreshold, setTopicThreshold] = useState(0.5);
  const [piiThreshold, setPIIThreshold] = useState(0.5);

  const [topicList, setTopicList] = useState<string[]>([]);
  const [piiList, setPIIList] = useState<string[]>([]);
  return (
    <SideDialog open={open} setOpen={setOpen}>
      <div className="pb-20">
        <div className="pb-8">
          <SheetTitle>Set a guardrail</SheetTitle>
          <div className="comet-body-s m-auto mt-4 w-[700px] self-center text-center text-muted-slate">
            Guardrails help you protect your application from risks inherent in
            LLMs. Use them to check the inputs and outputs of your LLM calls,
            and detect issues like off-topic answers or leaking sensitive
            information.
          </div>
        </div>
        <div className="m-auto flex w-full max-w-[1250px] items-start gap-6">
          <div className="flex w-[250px] shrink-0 flex-col">
            <GuardrailConfig
              title="Topic guardrail"
              hintText="The topic guardrail is designed to prevent the model from generating responses on certain topics that might be inappropriate, unsafe, unethical, or outside its intended scope."
            >
              <GuardrailConfig.Threshold
                id="topic-threshold"
                value={topicThreshold}
                onChange={setTopicThreshold}
              />
              <GuardrailConfig.TopicsList
                id="topic-list"
                onChange={setTopicList}
                value={topicList}
              />
            </GuardrailConfig>
            <Separator className="my-1" />
            <GuardrailConfig
              title="PII guardrail"
              hintText="The PII (Personally Identifiable Information) guardrail is designed to prevent the model from generating, storing, or processing sensitive personal data that could identify individuals."
            >
              <GuardrailConfig.Threshold
                id="pii-threshold"
                value={piiThreshold}
                onChange={setPIIThreshold}
              />
              <GuardrailConfig.RestrictedList
                id="pii-list"
                value={piiList}
                onChange={setPIIList}
              />
            </GuardrailConfig>
            <Separator className="my-1" />
          </div>
          <div className="flex w-full max-w-[700px] flex-col gap-2 rounded-md border border-slate-200 p-6">
            <div className="comet-body-s mt-4 text-foreground-secondary">
              2. Install the SDK
            </div>
            <CodeHighlighter data={"pip install opik"} />
            <div className="comet-body-s mt-4 text-foreground-secondary">
              Use the following code to configure your guardrail
            </div>
            <CreateExperimentCode
              code={`from opik.guardrails import Guardrail, PII, Topic
from opik import exceptions
guard = Guardrail(
    guards=[
        Topic(restricted_topics=["finance"], threshold=0.8),
        PII(blocked_entities=["CREDIT_CARD", "PERSON"], threshold=0.4),
    ]
)

result = guard.validate("How can I start with evaluation in Opik 
platform?")
# Guardrail passes

try:
    result = guard.validate("Where should I invest my money?")
except exceptions.GuardrailValidationFailed as e:
    print("Guardrail failed:", e)

try:
    result = guard.validate("John Doe, here is my card number 4111111111111111 how can I use it in Opik platform?.")
except exceptions.GuardrailValidationFailed as e:
    print("Guardrail failed:", e)`}
            />
          </div>

          <div className="flex w-[250px] shrink-0 flex-col gap-6 self-start">
            <ApiKeyCard />
          </div>
        </div>
      </div>
    </SideDialog>
  );
};

export default SetGuardrailDialog;
