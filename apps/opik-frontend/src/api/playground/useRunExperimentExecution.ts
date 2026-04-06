import { useMutation } from "@tanstack/react-query";
import api, { EXPERIMENT_EXECUTION_REST_ENDPOINT } from "@/api/api";
import { snakeCaseObj } from "@/lib/utils";
import { PlaygroundPromptType } from "@/types/playground";

interface ExperimentInfo {
  experiment_id: string;
  prompt_index: number;
}

export interface ExperimentExecutionResponse {
  experiments: ExperimentInfo[];
  total_items: number;
}

interface UseRunExperimentExecutionParams {
  datasetName: string;
  datasetVersionId?: string;
  datasetId?: string;
  versionHash?: string;
  prompts: PlaygroundPromptType[];
  projectName?: string;
}

const runExperimentExecution = async ({
  datasetName,
  datasetVersionId,
  datasetId,
  versionHash,
  prompts,
  projectName,
}: UseRunExperimentExecutionParams): Promise<ExperimentExecutionResponse> => {
  const promptVariants = prompts.map((prompt) => {
    const configs = snakeCaseObj(prompt.configs) as Record<string, unknown>;

    return {
      model: prompt.model,
      messages: prompt.messages.map((msg) => snakeCaseObj(msg)),
      configs,
      prompt_versions: prompt.loadedChatPromptId
        ? [{ id: prompt.loadedChatPromptId }]
        : undefined,
    };
  });

  const request = {
    dataset_name: datasetName,
    dataset_version_id: datasetVersionId,
    prompts: promptVariants,
    project_name: projectName,
    dataset_id: datasetId,
    version_hash: versionHash,
  };

  const { data } = await api.post<ExperimentExecutionResponse>(
    EXPERIMENT_EXECUTION_REST_ENDPOINT,
    request,
  );

  return data;
};

export default function useRunExperimentExecution() {
  return useMutation({
    mutationFn: runExperimentExecution,
  });
}
