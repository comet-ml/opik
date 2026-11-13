import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  AUTOMATIONS_REST_ENDPOINT,
  EVENT_AUTOMATIONS_KEY,
  QueryConfig,
} from "@/api/api";
import { Loop } from "@/types/event-automations";

type UseLoopParams = {
  projectId: string;
};

const getLoop = async (
  { signal }: QueryFunctionContext,
  { projectId }: UseLoopParams,
) => {
  const { data } = await api.get<Loop>(`${AUTOMATIONS_REST_ENDPOINT}loop`, {
    signal,
    params: { project_id: projectId },
  });

  return data;
};

export default function useLoop(
  params: UseLoopParams,
  options?: QueryConfig<Loop>,
) {
  return useQuery({
    queryKey: [EVENT_AUTOMATIONS_KEY, { resource: "loop", ...params }],
    queryFn: (context) => getLoop(context, params),
    ...options,
  });
}
