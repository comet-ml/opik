import { useMutation, useQueryClient } from "@tanstack/react-query";
import { v4 as uuidv4 } from "uuid";

const CONFIG_BACKEND_URL = "http://localhost:5050";

type ExperimentVariable = {
  key: string;
  value: string | number | boolean;
};

type CreateExperimentParams = {
  variables: ExperimentVariable[];
  experimentId?: string;
  name?: string;
  projectId?: string;
  isAb?: boolean;
  distribution?: Record<string, number>;
};

type CreateExperimentResult = {
  experimentId: string;
  name: string;
  variables: ExperimentVariable[];
};

const createExperiment = async ({
  variables,
  experimentId = uuidv4(),
  name,
  projectId = "default",
  isAb = false,
  distribution,
}: CreateExperimentParams): Promise<CreateExperimentResult> => {
  // Create the mask first
  const maskRes = await fetch(`${CONFIG_BACKEND_URL}/v1/config/masks`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      project_id: projectId,
      env: "prod",
      mask_id: experimentId,
      name: name || undefined,
      is_ab: isAb,
      distribution: distribution,
    }),
  });

  if (!maskRes.ok) {
    throw new Error("Failed to create experiment");
  }

  const maskData = await maskRes.json();
  const generatedName = maskData.name;

  // Set overrides for each variable
  const variant = isAb ? "A" : "default";
  for (const variable of variables) {
    const response = await fetch(`${CONFIG_BACKEND_URL}/v1/config/masks/override`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        project_id: projectId,
        env: "prod",
        mask_id: experimentId,
        variant: variant,
        key: variable.key,
        value: variable.value,
      }),
    });

    if (!response.ok) {
      throw new Error(`Failed to set override for ${variable.key}`);
    }
  }

  return { experimentId, name: generatedName, variables };
};

type UseCreateExperimentParams = {
  projectId?: string;
};

const useCreateExperiment = (params: UseCreateExperimentParams = {}) => {
  const { projectId = "default" } = params;
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (mutationParams: Omit<CreateExperimentParams, "projectId">) =>
      createExperiment({ ...mutationParams, projectId }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["config-variables", projectId] });
    },
  });
};

export default useCreateExperiment;
