import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  AUTOMATIONS_REST_ENDPOINT,
  EVENT_AUTOMATIONS_KEY,
  QueryConfig,
} from "@/api/api";
import { AutomationRunsListResponse } from "@/types/event-automations";

type UseAutomationRunsListParams = {
  automationId: string;
  page: number;
  size: number;
};

const getAutomationRunsList = async (
  { signal }: QueryFunctionContext,
  { automationId, page, size }: UseAutomationRunsListParams,
) => {
  const { data } = await api.get<AutomationRunsListResponse>(
    `${AUTOMATIONS_REST_ENDPOINT}runs`,
    { signal, params: { automation_id: automationId, page, size } },
  );

  return data;
};

export default function useAutomationRunsList(
  params: UseAutomationRunsListParams,
  options?: QueryConfig<AutomationRunsListResponse>,
) {
  return useQuery({
    queryKey: [EVENT_AUTOMATIONS_KEY, { resource: "runs", ...params }],
    queryFn: (context) => getAutomationRunsList(context, params),
    ...options,
  });
}
