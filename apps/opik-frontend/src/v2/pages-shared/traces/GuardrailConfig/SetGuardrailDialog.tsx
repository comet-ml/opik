import React from "react";
import SideDialog from "@/shared/SideDialog/SideDialog";
import { SheetTopBar } from "@/ui/sheet";
import ApiKeyCard from "@/v2/pages-shared/onboarding/ApiKeyCard/ApiKeyCard";
import GuardrailConfig from "./GuardrailConfig";
import { Separator } from "@/ui/separator";
import DocsLinkCard from "@/v2/pages-shared/onboarding/DocsLinkCard/DocsLinkCard";
import GuardrailConfigCode from "./GuardrailConfigCode";
import { guardrailsMap } from "./guardrailsConfig";
import { useGuardrailConfigState } from "./useGuardrailConfigState";
import { GuardrailTypes } from "@/types/guardrails";
import { buildDocsUrl } from "@/v2/lib/utils";
import useAppStore from "@/store/AppStore";

const GUARDRAIL_DOCS_LINK = buildDocsUrl(
  "/production/gateway-guardrails/guardrails",
);

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
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const {
    state: guardrailsState,
    updateField,
    toggleEnabled,
    getEnabledGuardrailTypes,
  } = useGuardrailConfigState();

  const enabledGuardrailsKeys = getEnabledGuardrailTypes();
  const importCodeNames = enabledGuardrailsKeys.map(
    (key) => guardrailsMap[key].codeImportName,
  );
  const codeList = enabledGuardrailsKeys.map((key) =>
    guardrailsMap[key].codeBuilder({
      ...guardrailsState[key],
      entities: guardrailsState[key].entities
        .map((v) => v.trim())
        .filter((v) => v),
    }),
  );

  const TOPIC_CONFIG = guardrailsMap[GuardrailTypes.TOPIC];
  const PII_CONFIG = guardrailsMap[GuardrailTypes.PII];
  const PROMPT_INJECTION_CONFIG =
    guardrailsMap[GuardrailTypes.PROMPT_INJECTION];
  const CUSTOM_CLASSIFIER_CONFIG =
    guardrailsMap[GuardrailTypes.CUSTOM_CLASSIFIER];
  const LLM_JUDGE_CONFIG = guardrailsMap[GuardrailTypes.LLM_JUDGE];

  const TOPIC_STATE = guardrailsState[GuardrailTypes.TOPIC];
  const PII_STATE = guardrailsState[GuardrailTypes.PII];
  const PROMPT_INJECTION_STATE =
    guardrailsState[GuardrailTypes.PROMPT_INJECTION];
  const CUSTOM_CLASSIFIER_STATE =
    guardrailsState[GuardrailTypes.CUSTOM_CLASSIFIER];
  const LLM_JUDGE_STATE = guardrailsState[GuardrailTypes.LLM_JUDGE];

  return (
    <SideDialog
      open={open}
      setOpen={setOpen}
      header={<SheetTopBar variant="form" title="Set a guardrail" />}
    >
      <div className="max-h-full overflow-y-auto p-6 pb-20 pt-4">
        <div className="comet-body-s m-auto mb-8 w-[700px] self-center text-center text-muted-slate">
          Guardrails help you protect your application from risks inherent in
          LLMs. Use them to check the inputs and outputs of your LLM calls, and
          detect issues like off-topic answers or leaking sensitive information.
        </div>
        <div className="m-auto flex w-full max-w-[1250px] items-start gap-6">
          <div className="flex w-[250px] shrink-0 flex-col">
            <GuardrailConfig
              title={TOPIC_CONFIG.title}
              hintText={TOPIC_CONFIG.hintText}
              enabled={TOPIC_STATE.enabled}
              toggleEnabled={() => toggleEnabled(GuardrailTypes.TOPIC)}
            >
              <GuardrailConfig.Threshold
                id={`${TOPIC_CONFIG.id}-threshold`}
                value={TOPIC_STATE.threshold}
                onChange={(v) =>
                  updateField(GuardrailTypes.TOPIC, "threshold", v)
                }
              />
              <GuardrailConfig.TopicsList
                id={`${TOPIC_CONFIG.id}-list`}
                onChange={(v) =>
                  updateField(GuardrailTypes.TOPIC, "entities", v)
                }
                value={TOPIC_STATE.entities}
              />
            </GuardrailConfig>
            <Separator className="my-1" />
            <GuardrailConfig
              title={PII_CONFIG.title}
              hintText={PII_CONFIG.hintText}
              enabled={PII_STATE.enabled}
              toggleEnabled={() => toggleEnabled(GuardrailTypes.PII)}
            >
              <GuardrailConfig.Threshold
                id={`${PII_CONFIG.id}-threshold`}
                value={PII_STATE.threshold}
                onChange={(v) =>
                  updateField(GuardrailTypes.PII, "threshold", v)
                }
              />
              <GuardrailConfig.RestrictedList
                value={PII_STATE.entities}
                onChange={(v) => updateField(GuardrailTypes.PII, "entities", v)}
              />
            </GuardrailConfig>
            <Separator className="my-1" />
            <GuardrailConfig
              title={PROMPT_INJECTION_CONFIG.title}
              hintText={PROMPT_INJECTION_CONFIG.hintText}
              enabled={PROMPT_INJECTION_STATE.enabled}
              toggleEnabled={() =>
                toggleEnabled(GuardrailTypes.PROMPT_INJECTION)
              }
            >
              <GuardrailConfig.Threshold
                id={`${PROMPT_INJECTION_CONFIG.id}-threshold`}
                value={PROMPT_INJECTION_STATE.threshold}
                onChange={(v) =>
                  updateField(GuardrailTypes.PROMPT_INJECTION, "threshold", v)
                }
              />
            </GuardrailConfig>
            <Separator className="my-1" />
            <GuardrailConfig
              title={CUSTOM_CLASSIFIER_CONFIG.title}
              hintText={CUSTOM_CLASSIFIER_CONFIG.hintText}
              enabled={CUSTOM_CLASSIFIER_STATE.enabled}
              toggleEnabled={() =>
                toggleEnabled(GuardrailTypes.CUSTOM_CLASSIFIER)
              }
            >
              <GuardrailConfig.TextInput
                id={`${CUSTOM_CLASSIFIER_CONFIG.id}-model-name`}
                label="Model name"
                placeholder="my-trained-model"
                value={CUSTOM_CLASSIFIER_STATE.modelName}
                onChange={(v) =>
                  updateField(GuardrailTypes.CUSTOM_CLASSIFIER, "modelName", v)
                }
              />
              <GuardrailConfig.Threshold
                id={`${CUSTOM_CLASSIFIER_CONFIG.id}-threshold`}
                value={CUSTOM_CLASSIFIER_STATE.threshold}
                onChange={(v) =>
                  updateField(GuardrailTypes.CUSTOM_CLASSIFIER, "threshold", v)
                }
              />
            </GuardrailConfig>
            <Separator className="my-1" />
            <GuardrailConfig
              title={LLM_JUDGE_CONFIG.title}
              hintText={LLM_JUDGE_CONFIG.hintText}
              enabled={LLM_JUDGE_STATE.enabled}
              toggleEnabled={() => toggleEnabled(GuardrailTypes.LLM_JUDGE)}
            >
              <GuardrailConfig.TextInput
                id={`${LLM_JUDGE_CONFIG.id}-name`}
                label="Check name"
                placeholder="my-policy-check"
                value={LLM_JUDGE_STATE.name}
                onChange={(v) =>
                  updateField(GuardrailTypes.LLM_JUDGE, "name", v)
                }
              />
              <GuardrailConfig.Instructions
                id={`${LLM_JUDGE_CONFIG.id}-instructions`}
                value={LLM_JUDGE_STATE.instructions}
                onChange={(v) =>
                  updateField(GuardrailTypes.LLM_JUDGE, "instructions", v)
                }
              />
              <GuardrailConfig.ModelSelect
                value={LLM_JUDGE_STATE.model}
                workspaceName={workspaceName}
                onChange={(v) =>
                  updateField(GuardrailTypes.LLM_JUDGE, "model", v)
                }
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
