import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { OllieReportPage } from "@/types/ollie-reports";

export const REPORTS_KEY = "reports";

type UseReportsParams = {
  projectId: string;
  page?: number;
  size?: number;
};

const getReports = async (
  { signal }: QueryFunctionContext,
  { projectId, page = 1, size = 10 }: UseReportsParams,
) => {
  const { data } = await api.get<OllieReportPage>(
    `${PROJECTS_REST_ENDPOINT}${projectId}/reports`,
    {
      signal,
      params: { page, size },
    },
  );

  return data;
};

export default function useReports(
  params: UseReportsParams,
  options?: QueryConfig<OllieReportPage>,
) {
  return useQuery({
    queryKey: [REPORTS_KEY, params],
    queryFn: (context) => getReports(context, params),
    ...options,
  });
}
