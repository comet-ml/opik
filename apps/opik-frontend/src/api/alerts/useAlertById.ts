import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { ALERTS_KEY, ALERTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Alert } from "@/types/alerts";

const getAlertById = async (
  { signal }: QueryFunctionContext,
  { alertId }: UseAlertByIdParams,
) => {
  const { data } = await api.get(`${ALERTS_REST_ENDPOINT}${alertId}`, {
    signal,
  });

  return data;
};

type UseAlertByIdParams = {
  alertId: string;
};

export default function useAlertById(
  params: UseAlertByIdParams,
  options?: QueryConfig<Alert>,
) {
  return useQuery({
    queryKey: [ALERTS_KEY, params],
    queryFn: (context) => getAlertById(context, params),
    ...options,
  });
}
