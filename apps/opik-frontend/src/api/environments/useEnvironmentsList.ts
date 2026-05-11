import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  ENVIRONMENTS_KEY,
  ENVIRONMENTS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { Environment } from "@/types/environments";

export type UseEnvironmentsListResponse = {
  content: Environment[];
  page: number;
  size: number;
  total: number;
  sortable_by: string[];
};

const getEnvironmentsList = async ({ signal }: QueryFunctionContext) => {
  const { data } = await api.get<UseEnvironmentsListResponse>(
    ENVIRONMENTS_REST_ENDPOINT,
    { signal },
  );

  return data;
};

export default function useEnvironmentsList(
  options?: QueryConfig<UseEnvironmentsListResponse>,
) {
  return useQuery({
    queryKey: [ENVIRONMENTS_KEY, {}],
    queryFn: (context) => getEnvironmentsList(context),
    ...options,
  });
}
