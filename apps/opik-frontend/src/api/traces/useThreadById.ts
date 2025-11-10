import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, THREADS_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { Thread } from "@/types/traces";

type UseThreadByIdParams = {
  projectId: string;
  threadId: string;
};

const getThreadById = async (
  { signal }: QueryFunctionContext,
  { projectId, threadId }: UseThreadByIdParams,
) => {
  const { data } = await api.post<Thread>(
    `${TRACES_REST_ENDPOINT}threads/retrieve`,
    {
      thread_id: threadId,
      project_id: projectId,
      truncate: false,
    },
    {
      signal,
    },
  );

  return data;
};

export default function useThreadById(
  params: UseThreadByIdParams,
  options?: QueryConfig<Thread>,
) {
  return useQuery({
    queryKey: [THREADS_KEY, params],
    queryFn: (context) => getThreadById(context, params),
    ...options,
  });
}
