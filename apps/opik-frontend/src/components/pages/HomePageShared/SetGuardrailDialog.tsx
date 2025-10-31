import React from "react";
import SideDialog from "@/components/shared/SideDialog/SideDialog";
import { SheetTitle } from "@/components/ui/sheet";
import ApiKeyCard from "@/components/pages-shared/onboarding/ApiKeyCard/ApiKeyCard";
import GuardrailConfig from "./GuardrailConfig";
import { Separator } from "@/components/ui/separator";
import DocsLinkCard from "@/components/pages-shared/onboarding/DocsLinkCard/DocsLinkCard";
import GuardrailConfigCode from "./GuardrailConfigCode";
import { guardrailsMap } from "./guardrailsConfig";
import { useGuardrailConfigState } from "./useGuardrailConfigState";
import { GuardrailTypes } from "@/types/guardrails";

const GUARDRAIL_DOCS_LINK =
  "https://www.comet.com/docs/opik/production/guardrails";

type SetGuardrailDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  projectName?: string;
};

const SetGuardrailDialog: React.FC<SetGuardrailDialogProps> = ({
  open,
  setOpen,
  projectName,
}) => {
  const {
    state: guardrailsState,
    updateThreshold,
    updateEntities,
    toggleEnabled,
    getEnabledGuardrailTypes,
  } = useGuardrailConfigState();

  const enabledGuardrailsKeys = getEnabledGuardrailTypes() as GuardrailTypes[];
  const importCodeNames = enabledGuardrailsKeys.map(
    (key) => guardrailsMap[key].codeImportName,
  );
  const codeList = enabledGuardrailsKeys.map((key) =>
    guardrailsMap[key].codeBuilder(
      guardrailsState[key].entities.map((v) => v.trim()).filter((v) => v),
      guardrailsState[key].threshold,
    ),
  );

  const TOPIC_GUARDRAIL_CONFIG = guardrailsMap[GuardrailTypes.TOPIC];
  const PII_GUARDRAIL_CONFIG = guardrailsMap[GuardrailTypes.PII];

  const TOPIC_GUARDRAIL_STATE = guardrailsState[GuardrailTypes.TOPIC];
  const PII_GUARDRAIL_STATE = guardrailsState[GuardrailTypes.PII];

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
              title={TOPIC_GUARDRAIL_CONFIG.title}
              hintText={TOPIC_GUARDRAIL_CONFIG.hintText}
              enabled={TOPIC_GUARDRAIL_STATE.enabled}
              toggleEnabled={() => toggleEnabled(GuardrailTypes.TOPIC)}
            >
              <GuardrailConfig.Threshold
                id={`${TOPIC_GUARDRAIL_CONFIG.id}-threshold`}
                value={TOPIC_GUARDRAIL_STATE.threshold}
                onChange={(v) => updateThreshold(GuardrailTypes.TOPIC, v)}
              />
              <GuardrailConfig.TopicsList
                id={`${TOPIC_GUARDRAIL_CONFIG.id}-list`}
                onChange={(v) => updateEntities(GuardrailTypes.TOPIC, v)}
                value={TOPIC_GUARDRAIL_STATE.entities}
              />
            </GuardrailConfig>
            <Separator className="my-1" />
            <GuardrailConfig
              title={PII_GUARDRAIL_CONFIG.title}
              hintText={PII_GUARDRAIL_CONFIG.hintText}
              enabled={PII_GUARDRAIL_STATE.enabled}
              toggleEnabled={() => toggleEnabled(GuardrailTypes.PII)}
            >
              <GuardrailConfig.Threshold
                id={`${PII_GUARDRAIL_CONFIG.id}-threshold`}
                value={PII_GUARDRAIL_STATE.threshold}
                onChange={(v) => updateThreshold(GuardrailTypes.PII, v)}
              />
              <GuardrailConfig.RestrictedList
                value={PII_GUARDRAIL_STATE.entities}
                onChange={(v) => updateEntities(GuardrailTypes.PII, v)}
              />
            </GuardrailConfig>
            <Separator className="my-1" />
          </div>
          <div className="flex w-full max-w-[700px] flex-col gap-2 rounded-md border border-border p-6">
            <GuardrailConfigCode
              codeImportNames={importCodeNames}
              codeList={codeList}
              projectName={projectName}
            />
          </div>

          <div className="flex w-[250px] shrink-0 flex-col gap-6 self-start">
            <ApiKeyCard />
            <DocsLinkCard
              description="Learn how to configure your guardrails in our docs."
              link={GUARDRAIL_DOCS_LINK}
            />
          </div>
        </div>
      </div>
    </SideDialog>
  );
};

export default SetGuardrailDialog;
