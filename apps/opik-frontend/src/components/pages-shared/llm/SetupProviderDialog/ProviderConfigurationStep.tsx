import React from "react";
import { UseFormReturn } from "react-hook-form";
import { MessageCircleWarning } from "lucide-react";
import { PROVIDER_TYPE } from "@/types/providers";
import { PROVIDERS } from "@/constants/providers";
import { Form } from "@/components/ui/form";
import CloudAIProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/CloudAIProviderDetails";
import CustomProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/CustomProviderDetails";
import VertexAIProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/VertexAIProviderDetails";
import BedrockProviderDetails from "@/components/pages-shared/llm/ManageAIProviderDialog/BedrockProviderDetails";
import { AIProviderFormType } from "@/components/pages-shared/llm/ManageAIProviderDialog/schema";
import ExplainerCallout from "@/components/shared/ExplainerCallout/ExplainerCallout";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

interface ProviderConfigurationStepProps {
  selectedProviderType: PROVIDER_TYPE;
  form: UseFormReturn<AIProviderFormType>;
  onSubmit: (data: AIProviderFormType) => void;
  isEdit?: boolean;
  customProviderName?: string;
}

const ProviderConfigurationStep: React.FC<ProviderConfigurationStepProps> = ({
  selectedProviderType,
  form,
  onSubmit,
  isEdit = false,
  customProviderName,
}) => {
  const providerConfig = PROVIDERS[selectedProviderType];
  const Icon = providerConfig?.icon;
  const providerLabel =
    customProviderName || providerConfig?.label || selectedProviderType;

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-3 rounded-lg border border-border p-3">
        {Icon && <Icon className="size-8 text-foreground" />}
        <div>
          <p className="comet-body-s text-foreground">{providerLabel}</p>
        </div>
      </div>

      {isEdit && (
        <ExplainerCallout
          Icon={MessageCircleWarning}
          isDismissable={false}
          {...EXPLAINERS_MAP[
            EXPLAINER_ID.what_happens_if_i_edit_an_ai_provider
          ]}
        />
      )}

      <Form {...form}>
        <form
          onSubmit={form.handleSubmit(onSubmit)}
          className="flex flex-col gap-4"
        >
          {selectedProviderType === PROVIDER_TYPE.CUSTOM ? (
            <CustomProviderDetails form={form} isEdit={isEdit} />
          ) : selectedProviderType === PROVIDER_TYPE.BEDROCK ? (
            <BedrockProviderDetails form={form} isEdit={isEdit} />
          ) : selectedProviderType === PROVIDER_TYPE.VERTEX_AI ? (
            <VertexAIProviderDetails form={form} />
          ) : (
            <CloudAIProviderDetails
              provider={selectedProviderType}
              form={form}
            />
          )}
        </form>
      </Form>
    </div>
  );
};

export default ProviderConfigurationStep;
