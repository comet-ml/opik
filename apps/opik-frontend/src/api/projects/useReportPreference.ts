import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { ReportPreference } from "@/types/ollie-reports";

export const REPORT_PREFERENCE_KEY = "report-preference";

type UseReportPreferenceParams = {
  projectId: string;
};

const getReportPreference = async (
  { signal }: QueryFunctionContext,
  { projectId }: UseReportPreferenceParams,
) => {
  const { data } = await api.get<ReportPreference | null>(
    `${PROJECTS_REST_ENDPOINT}${projectId}/reports/preferences`,
    { signal },
  );

  return data;
};

export default function useReportPreference(
  params: UseReportPreferenceParams,
  options?: QueryConfig<ReportPreference | null>,
) {
  return useQuery({
    queryKey: [REPORT_PREFERENCE_KEY, params],
    queryFn: (context) => getReportPreference(context, params),
    ...options,
  });
}
