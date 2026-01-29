import { useMutation } from "@tanstack/react-query";
import api, { OLLAMA_REST_ENDPOINT } from "@/api/api";
import { AxiosError } from "axios";

export type OllamaInstanceBaseUrlRequest = {
  base_url: string;
  api_key?: string;
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
    OllamaInstanceBaseUrlRequest
  >({
    mutationFn: async (request: OllamaInstanceBaseUrlRequest) => {
      const { data } = await api.post(
        `${OLLAMA_REST_ENDPOINT}test-connection`,
        request,
      );
      return data;
    },
  });
};

export default useOllamaTestConnectionMutation;
