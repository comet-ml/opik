import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  ASSISTANT_SIDEBAR_CONFIG_REST_ENDPOINT,
  ASSISTANT_SIDEBAR_CONFIG_KEY,
  QueryConfig,
} from "@/api/api";

interface AssistantSidebarConfig {
  enabled: boolean;
  manifest_url: string;
}

const DEFAULT_CONFIG: AssistantSidebarConfig = {
  enabled: false,
  manifest_url: "",
};

const getAssistantSidebarConfig = async ({
  signal,
}: QueryFunctionContext): Promise<AssistantSidebarConfig> => {
  const { data, status } = await api.get<AssistantSidebarConfig>(
    ASSISTANT_SIDEBAR_CONFIG_REST_ENDPOINT,
    {
      signal,
      validateStatus: (s) => (s >= 200 && s < 300) || s === 404,
    },
  );

  return status === 404 ? DEFAULT_CONFIG : data;
};

export default function useAssistantSidebarConfig(
  options?: QueryConfig<AssistantSidebarConfig>,
) {
  return useQuery({
    queryKey: [ASSISTANT_SIDEBAR_CONFIG_KEY, {}],
    queryFn: getAssistantSidebarConfig,
    staleTime: Infinity,
    ...options,
  });
}
