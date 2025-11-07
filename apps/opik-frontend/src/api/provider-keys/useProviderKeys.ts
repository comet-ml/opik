import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  PROVIDER_KEYS_REST_ENDPOINT,
  PROVIDERS_KEYS_KEY,
  QueryConfig,
} from "@/api/api";
import { ProviderObject } from "@/types/providers";
import { buildComposedProviderKey } from "@/lib/provider";

type UseProviderKeysListParams = {
  workspaceName: string;
};

type UseProviderKeysListResponse = {
  content: ProviderObject[];
  total: number;
};

const getProviderKeys = async ({ signal }: QueryFunctionContext) => {
  const { data } = await api.get(PROVIDER_KEYS_REST_ENDPOINT, {
    signal,
  });

  return {
    ...data,
    content: data.content.map((provider: ProviderObject) => ({
      ...provider,
      ui_composed_provider: buildComposedProviderKey(
        provider.provider,
        provider.provider_name,
      ),
    })),
  };
};

export default function useProviderKeys(
  params: UseProviderKeysListParams,
  options?: QueryConfig<UseProviderKeysListResponse>,
) {
  return useQuery({
    queryKey: [PROVIDERS_KEYS_KEY, params],
    queryFn: (context) => getProviderKeys(context),
    ...options,
  });
}
