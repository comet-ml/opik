import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import axios from "axios";
import { BlueprintHistory } from "@/types/blueprints";

const CONFIG_SERVICE_URL =
  import.meta.env.VITE_CONFIG_SERVICE_URL || "http://localhost:5050";

type UseBlueprintHistoryParams = {
  blueprintId: string;
  limit?: number;
  enabled?: boolean;
};

const getBlueprintHistory = async (
  { signal }: QueryFunctionContext,
  blueprintId: string,
  limit: number,
): Promise<BlueprintHistory> => {
  const { data } = await axios.get(
    `${CONFIG_SERVICE_URL}/v1/blueprints/${blueprintId}/history`,
    {
      signal,
      params: { limit },
    },
  );
  return data;
};

export default function useBlueprintHistory({
  blueprintId,
  limit = 50,
  enabled = true,
}: UseBlueprintHistoryParams) {
  return useQuery({
    queryKey: ["blueprint", "history", blueprintId, { limit }],
    queryFn: (context) => getBlueprintHistory(context, blueprintId, limit),
    enabled: enabled && !!blueprintId,
    refetchInterval: 10000,
  });
}
