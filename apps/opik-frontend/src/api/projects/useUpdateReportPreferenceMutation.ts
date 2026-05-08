import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT } from "@/api/api";
import { ReportPreference } from "@/types/ollie-reports";
import { REPORT_PREFERENCE_KEY } from "@/api/projects/useReportPreference";

type UseUpdateReportPreferenceMutationParams = {
  projectId: string;
  enabled: boolean;
};

const useUpdateReportPreferenceMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      projectId,
      enabled,
    }: UseUpdateReportPreferenceMutationParams) => {
      const { data } = await api.put<ReportPreference>(
        `${PROJECTS_REST_ENDPOINT}${projectId}/reports/preferences`,
        { enabled },
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
