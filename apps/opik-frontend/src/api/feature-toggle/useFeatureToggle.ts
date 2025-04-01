import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import isBoolean from "lodash/isBoolean";
import api, { FEATURE_TOGGLES_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { FeatureToggles } from "@/types/feature-toggles";

type UseFeatureTogglesListParams = {
  workspaceName: string;
};

const getFeatureToggles = async ({ signal }: QueryFunctionContext) => {
  const { data } = await api.get<FeatureToggles>(
    FEATURE_TOGGLES_REST_ENDPOINT,
    {
      signal,
    },
  );

  return data;
};

export default function useFeatureToggle(
  params: UseFeatureTogglesListParams,
  options?: QueryConfig<FeatureToggles>,
) {
  return useQuery({
    queryKey: ["feature-toggles", params],
    queryFn: (context) => getFeatureToggles(context),
    ...options,
    enabled: isBoolean(options?.enabled)
      ? options?.enabled
      : Boolean(params.workspaceName),
  });
}
