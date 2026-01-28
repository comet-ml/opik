import { useMutation } from "@tanstack/react-query";
import api, { OLLAMA_REST_ENDPOINT } from "@/api/api";
import { AxiosError } from "axios";

export type OllamaModel = {
  name: string;
  size: number;
  digest: string;
  modified_at: string;
};

export type OllamaListModelsRequest = {
  base_url: string;
  api_key?: string;
};

const useOllamaListModelsMutation = () => {
  return useMutation<OllamaModel[], AxiosError, OllamaListModelsRequest>({
    mutationFn: async (request: OllamaListModelsRequest) => {
      const { data } = await api.post(
        `${OLLAMA_REST_ENDPOINT}models`,
        request,
      );
      return data;
    },
  });
};

export default useOllamaListModelsMutation;
