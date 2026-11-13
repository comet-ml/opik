import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  AUTOMATIONS_REST_ENDPOINT,
  EVENT_AUTOMATIONS_KEY,
  QueryConfig,
} from "@/api/api";
import { AutomationRun } from "@/types/event-automations";

type UseAutomationRunByIdParams = {
  runId: string;
};

const getAutomationRunById = async (
  { signal }: QueryFunctionContext,
  { runId }: UseAutomationRunByIdParams,
) => {
  const { data } = await api.get<AutomationRun>(
    `${AUTOMATIONS_REST_ENDPOINT}runs/${runId}`,
    { signal },
  );

  return data;
};

export default function useAutomationRunById(
  params: UseAutomationRunByIdParams,
  options?: QueryConfig<AutomationRun>,
) {
  return useQuery({
    queryKey: [EVENT_AUTOMATIONS_KEY, { resource: "run", ...params }],
    queryFn: (context) => getAutomationRunById(context, params),
    ...options,
  });
}
