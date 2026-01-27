import { useMutation } from "@tanstack/react-query";
import api, { PROVIDER_KEYS_REST_ENDPOINT } from "@/api/api";
import { AxiosError } from "axios";

export type OllamaConnectionTestRequest = {
  base_url: string;
};

export type OllamaConnectionTestResponse = {
  connected: boolean;
  version?: string;
  error_message?: string;
};

const useOllamaTestConnectionMutation = () => {
  return useMutation<
    OllamaConnectionTestResponse,
    AxiosError,
    OllamaConnectionTestRequest
  >({
    mutationFn: async (request: OllamaConnectionTestRequest) => {
      const { data } = await api.post(
        `${PROVIDER_KEYS_REST_ENDPOINT}../ollama/test-connection`,
        request,
      );
      return data;
    },
  });
};

export default useOllamaTestConnectionMutation;
