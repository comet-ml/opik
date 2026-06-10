import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  ENVIRONMENT_KEY,
  ENVIRONMENTS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { Environment } from "@/types/environments";
import useQueryErrorToast from "@/hooks/useQueryErrorToast";

type UseEnvironmentByIdParams = {
  environmentId: string;
};

const getEnvironmentById = async (
  { signal }: QueryFunctionContext,
  { environmentId }: UseEnvironmentByIdParams,
) => {
  const { data } = await api.get<Environment>(
    ENVIRONMENTS_REST_ENDPOINT + environmentId,
    { signal },
  );

  return data;
};

export default function useEnvironmentById(
  params: UseEnvironmentByIdParams,
  options?: QueryConfig<Environment>,
) {
  const query = useQuery({
    queryKey: [ENVIRONMENT_KEY, params],
    queryFn: (context) => getEnvironmentById(context, params),
    ...options,
  });

  useQueryErrorToast({ isError: query.isError, error: query.error });

  return query;
}
