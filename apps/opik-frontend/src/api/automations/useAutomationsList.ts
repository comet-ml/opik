import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  AUTOMATIONS_REST_ENDPOINT,
  EVENT_AUTOMATIONS_KEY,
  QueryConfig,
} from "@/api/api";
import { AutomationsListResponse } from "@/types/event-automations";

type UseAutomationsListParams = {
  projectId: string;
  page: number;
  size: number;
};

const getAutomationsList = async (
  { signal }: QueryFunctionContext,
  { projectId, page, size }: UseAutomationsListParams,
) => {
  const { data } = await api.get<AutomationsListResponse>(
    AUTOMATIONS_REST_ENDPOINT,
    { signal, params: { project_id: projectId, page, size } },
  );

  return data;
};

export default function useAutomationsList(
  params: UseAutomationsListParams,
  options?: QueryConfig<AutomationsListResponse>,
) {
  return useQuery({
    queryKey: [EVENT_AUTOMATIONS_KEY, { resource: "automations", ...params }],
    queryFn: (context) => getAutomationsList(context, params),
    ...options,
  });
}
