import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import { OpenAiPipelineMode, PROVIDER_TYPE } from "@/types/providers";
import { normalizeOpenAiPipelineMode } from "@/lib/provider";

const useOpenAiPipelineMode = (
  workspaceName: string,
): OpenAiPipelineMode | undefined => {
  const { data } = useProviderKeys({ workspaceName });

  // Not the Chat Completions fallback: a model change made before the keys load would then
  // coerce a stored max to high for good. Undefined lets updateProviderConfig leave it alone.
  if (!data) return undefined;

  const openAiKey = data.content.find(
    (key) => key.provider === PROVIDER_TYPE.OPEN_AI,
  );

  return normalizeOpenAiPipelineMode(
    openAiKey?.configuration?.openai_pipeline_mode,
  );
};

export default useOpenAiPipelineMode;
