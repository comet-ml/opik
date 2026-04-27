import { keepPreviousData, useQuery } from "@tanstack/react-query";

import api, { AGENT_CONFIGS_KEY, AGENT_CONFIGS_REST_ENDPOINT } from "@/api/api";
import { BlueprintDetails } from "@/types/agent-configs";
import useQueryErrorToast from "@/hooks/useQueryErrorToast";

type UseAgentConfigByIdParams = {
  blueprintId: string;
};

const getAgentConfigById = async (
  blueprintId: string,
  signal: AbortSignal,
): Promise<BlueprintDetails> => {
  const { data } = await api.get(
    `${AGENT_CONFIGS_REST_ENDPOINT}blueprints/${blueprintId}`,
    { signal },
  );
  data.values.sort((a: { key: string }, b: { key: string }) =>
    a.key.localeCompare(b.key),
  );
  return data;
};

export default function useAgentConfigById({
  blueprintId,
}: UseAgentConfigByIdParams) {
  const query = useQuery({
    queryKey: [AGENT_CONFIGS_KEY, "blueprints", blueprintId],
    queryFn: ({ signal }) => getAgentConfigById(blueprintId, signal),
    placeholderData: keepPreviousData,
    enabled: !!blueprintId,
  });

  useQueryErrorToast({ isError: query.isError, error: query.error });

  return query;
}
