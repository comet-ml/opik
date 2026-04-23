import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { UI_CONFIG_REST_ENDPOINT } from "@/api/api";
import { UIConfig } from "@/types/ui-config";

const getUIConfig = async ({ signal }: QueryFunctionContext) => {
  const { data } = await api.get<UIConfig>(UI_CONFIG_REST_ENDPOINT, {
    signal,
  });

  return data;
};

export default function useUIConfig() {
  return useQuery({
    queryKey: ["ui-config"],
    queryFn: getUIConfig,
  });
}
