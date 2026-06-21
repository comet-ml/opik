import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT } from "@/api/api";
import { ReportPreference } from "@/types/ollie-reports";
import { REPORT_PREFERENCE_KEY } from "@/api/projects/useReportPreference";

type UseUpdateReportPreferenceMutationParams = {
  projectId: string;
  enabled: boolean;
  schedule_time?: string;
  custom_prompt?: string | null;
};

const useUpdateReportPreferenceMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      projectId,
      enabled,
      schedule_time,
      custom_prompt,
    }: UseUpdateReportPreferenceMutationParams) => {
      const { data } = await api.put<ReportPreference>(
        `${PROJECTS_REST_ENDPOINT}${projectId}/reports/preferences`,
        { enabled, schedule_time, custom_prompt },
      );
      return data;
    },
    onSettled: () => {
      queryClient.invalidateQueries({
        queryKey: [REPORT_PREFERENCE_KEY],
      });
    },
  });
};

export default useUpdateReportPreferenceMutation;
