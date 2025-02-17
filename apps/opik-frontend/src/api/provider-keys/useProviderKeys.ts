import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  PROVIDER_KEYS_REST_ENDPOINT,
  PROVIDERS_KEYS_KEY,
  QueryConfig,
} from "@/api/api";
import { ProviderKey } from "@/types/providers";
import useLocalAIProviderData from "@/hooks/useLocalAIProviderData";

type UseProviderKeysListParams = {
  workspaceName: string;
};

type UseProviderKeysListResponse = {
  content: ProviderKey[];
  total: number;
};

const getProviderKeys = async (
  { signal }: QueryFunctionContext,
  extendWithLocalData: (
    data: UseProviderKeysListResponse,
  ) => UseProviderKeysListResponse,
) => {
  const { data } = await api.get(PROVIDER_KEYS_REST_ENDPOINT, {
    signal,
  });

  return extendWithLocalData(data);
};

export default function useProviderKeys(
  params: UseProviderKeysListParams,
  options?: QueryConfig<UseProviderKeysListResponse>,
) {
  const { extendWithLocalData } = useLocalAIProviderData();

  return useQuery({
    queryKey: [PROVIDERS_KEYS_KEY, params],
    queryFn: (context) => getProviderKeys(context, extendWithLocalData),
    ...options,
  });
}
