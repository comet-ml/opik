import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import { OpenAiPipelineMode, PROVIDER_TYPE } from "@/types/providers";
import { normalizeOpenAiPipelineMode } from "@/v2/pages-shared/llm/ManageAIProviderDialog/schema";

const useOpenAiPipelineMode = (workspaceName: string): OpenAiPipelineMode => {
  const { data } = useProviderKeys({ workspaceName });
  const openAiKey = data?.content.find(
    (key) => key.provider === PROVIDER_TYPE.OPEN_AI,
  );

  return normalizeOpenAiPipelineMode(
    openAiKey?.configuration?.openai_pipeline_mode,
  );
};

export default useOpenAiPipelineMode;
