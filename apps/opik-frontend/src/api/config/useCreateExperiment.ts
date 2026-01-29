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
};

type CreateExperimentResult = {
  experimentId: string;
  variables: ExperimentVariable[];
};

const createExperiment = async ({
  variables,
  experimentId = uuidv4(),
}: CreateExperimentParams): Promise<CreateExperimentResult> => {
  for (const variable of variables) {
    const response = await fetch(`${CONFIG_BACKEND_URL}/config/set`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        key: variable.key,
        value: variable.value,
        experiment_id: experimentId,
      }),
    });

    if (!response.ok) {
      throw new Error(`Failed to set ${variable.key}`);
    }
  }

  return { experimentId, variables };
};

const useCreateExperiment = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: createExperiment,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["config-variables"] });
    },
  });
};

export default useCreateExperiment;
