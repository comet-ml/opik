import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  AUTOMATIONS_REST_ENDPOINT,
  EVENT_AUTOMATIONS_KEY,
  QueryConfig,
} from "@/api/api";
import { Automation } from "@/types/event-automations";

type UseAutomationByIdParams = {
  automationId: string;
};

const getAutomationById = async (
  { signal }: QueryFunctionContext,
  { automationId }: UseAutomationByIdParams,
) => {
  const { data } = await api.get<Automation>(
    `${AUTOMATIONS_REST_ENDPOINT}${automationId}`,
    { signal },
  );

  return data;
};

export default function useAutomationById(
  params: UseAutomationByIdParams,
  options?: QueryConfig<Automation>,
) {
  return useQuery({
    queryKey: [EVENT_AUTOMATIONS_KEY, { resource: "automation", ...params }],
    queryFn: (context) => getAutomationById(context, params),
    ...options,
  });
}
