import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  AUTOMATIONS_REST_ENDPOINT,
  EVENT_AUTOMATIONS_KEY,
  QueryConfig,
} from "@/api/api";
import { Workflow } from "@/types/event-automations";

type UseWorkflowsListParams = {
  projectId: string;
};

const getWorkflowsList = async (
  { signal }: QueryFunctionContext,
  { projectId }: UseWorkflowsListParams,
) => {
  const { data } = await api.get<Workflow[]>(
    `${AUTOMATIONS_REST_ENDPOINT}workflows`,
    { signal, params: { project_id: projectId } },
  );

  return data;
};

export default function useWorkflowsList(
  params: UseWorkflowsListParams,
  options?: QueryConfig<Workflow[]>,
) {
  return useQuery({
    queryKey: [EVENT_AUTOMATIONS_KEY, { resource: "workflows", ...params }],
    queryFn: (context) => getWorkflowsList(context, params),
    ...options,
  });
}
