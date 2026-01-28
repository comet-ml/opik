import { useMutation } from "@tanstack/react-query";
import api, { PROVIDER_KEYS_REST_ENDPOINT } from "@/api/api";
import { AxiosError } from "axios";

export type OllamaModel = {
  name: string;
  size: number;
  digest: string;
  modified_at: string;
};

export type OllamaListModelsRequest = {
  base_url: string;
};

const useOllamaListModelsMutation = () => {
  return useMutation<OllamaModel[], AxiosError, OllamaListModelsRequest>({
    mutationFn: async (request: OllamaListModelsRequest) => {
      const { data } = await api.post(
        `${PROVIDER_KEYS_REST_ENDPOINT}../ollama/models`,
        request,
      );
      return data;
    },
  });
};

export default useOllamaListModelsMutation;
