import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  OPENROUTER_REGION_KEY,
  PROVIDER_KEYS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { OpenRouterRegionInfo } from "@/types/providers";

type UseOpenRouterRegionParams = {
  workspaceName: string;
};

const getOpenRouterRegion = async ({ signal }: QueryFunctionContext) => {
  const { data } = await api.get<OpenRouterRegionInfo>(
    `${PROVIDER_KEYS_REST_ENDPOINT}openrouter/region`,
    { signal },
  );

  return data;
};

export default function useOpenRouterRegion(
  params: UseOpenRouterRegionParams,
  options?: QueryConfig<OpenRouterRegionInfo>,
) {
  return useQuery({
    queryKey: [OPENROUTER_REGION_KEY, params],
    queryFn: (context) => getOpenRouterRegion(context),
    ...options,
  });
}
