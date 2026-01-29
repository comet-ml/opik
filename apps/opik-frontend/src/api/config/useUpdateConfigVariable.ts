import { useMutation, useQueryClient } from "@tanstack/react-query";

const CONFIG_BACKEND_URL = "http://localhost:5050";

type UpdateConfigParams = {
  key: string;
  value: string | number | boolean;
};

const updateConfigVariable = async ({
  key,
  value,
}: UpdateConfigParams): Promise<void> => {
  const response = await fetch(`${CONFIG_BACKEND_URL}/config/set`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ key, value }),
  });

  if (!response.ok) {
    throw new Error("Failed to update config");
  }
};

const useUpdateConfigVariable = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: updateConfigVariable,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["config-variables"] });
    },
  });
};

export default useUpdateConfigVariable;
