import { useMutation, useQueryClient } from "@tanstack/react-query";

const CONFIG_BACKEND_URL = "http://localhost:5050";

type UpdateConfigParams = {
  key: string;
  value: string | number | boolean;
  projectId?: string;
};

const updateConfigVariable = async ({
  key,
  value,
  projectId = "default",
}: UpdateConfigParams): Promise<void> => {
  const response = await fetch(`${CONFIG_BACKEND_URL}/v1/config/publish`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      project_id: projectId,
      env: "prod",
      key,
      value,
    }),
  });

  if (!response.ok) {
    throw new Error("Failed to update config");
  }
};

type UseUpdateConfigVariableParams = {
  projectId?: string;
};

const useUpdateConfigVariable = (params: UseUpdateConfigVariableParams = {}) => {
  const { projectId = "default" } = params;
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (mutationParams: Omit<UpdateConfigParams, "projectId">) =>
      updateConfigVariable({ ...mutationParams, projectId }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["config-variables", projectId] });
    },
  });
};

export default useUpdateConfigVariable;
