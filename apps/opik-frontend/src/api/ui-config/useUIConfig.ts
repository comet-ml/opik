import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, UI_CONFIG_REST_ENDPOINT } from "@/api/api";
import { UIConfig } from "@/types/ui-config";

const getUIConfig = async ({ signal }: QueryFunctionContext) => {
  const { data } = await api.get<UIConfig>(UI_CONFIG_REST_ENDPOINT, {
    signal,
  });

  return data;
};

export default function useUIConfig(options?: QueryConfig<UIConfig>) {
  return useQuery({
    queryKey: ["ui-config", {}],
    queryFn: (context) => getUIConfig(context),
    ...options,
  });
}
