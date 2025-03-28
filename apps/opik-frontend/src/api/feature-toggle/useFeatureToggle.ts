import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { FEATURE_TOGGLES_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { FeatureToggle } from "@/types/feature-toggle";

const getFeatureToggle = async ({ signal }: QueryFunctionContext) => {
  const { data } = await api.get<FeatureToggle>(FEATURE_TOGGLES_REST_ENDPOINT, {
    signal,
  });

  return data;
};

export default function useFeatureToggle(options?: QueryConfig<FeatureToggle>) {
  return useQuery({
    queryKey: ["feature-toggle", { enabled: true }],
    queryFn: (context) => getFeatureToggle(context),
    ...options,
  });
}
