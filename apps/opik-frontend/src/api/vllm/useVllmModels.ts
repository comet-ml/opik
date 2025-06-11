import {
  PROVIDER_MODEL_TYPE,
  PROVIDER_MODELS_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { useQuery } from "@tanstack/react-query";
import axios from "axios";

const fetchVllmModels = async (base_url: string) => {
  // Use axios to fetch instead of api, since api is withCredentials, and vllm server usually would have CORS response *
  // You cannot have withCredentials true with the server returning Access-Control-Allow-Origin as *
  const { data } = await axios.get(`${base_url}/models`);
  const models: string[] = data.data.map((item: { id: string }) => item.id);
  let retVal: Partial<PROVIDER_MODELS_TYPE> | undefined = undefined;
  if (models.length > 0) {
    retVal = {
      [PROVIDER_TYPE.VLLM]: models.map((m) => ({
        value: m.trim() as PROVIDER_MODEL_TYPE,
        label: m.split("/").pop()!,
      })),
    };
  }
  return retVal;
};

export default function useVllmModels(base_url?: string) {
  return useQuery({
    queryKey: ["vllm-models", base_url],
    queryFn: () => fetchVllmModels(base_url!),
    enabled: !!base_url,
  });
}
