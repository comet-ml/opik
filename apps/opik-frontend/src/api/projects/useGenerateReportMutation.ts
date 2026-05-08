import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT } from "@/api/api";
import { GenerateReportResponse } from "@/types/ollie-reports";
import { REPORTS_KEY } from "@/api/projects/useReports";

type UseGenerateReportMutationParams = {
  projectId: string;
};

const useGenerateReportMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ projectId }: UseGenerateReportMutationParams) => {
      const { data } = await api.post<GenerateReportResponse>(
        `${PROJECTS_REST_ENDPOINT}${projectId}/reports/generate`,
      );
      return data;
    },
    onSettled: () => {
      queryClient.invalidateQueries({
        queryKey: [REPORTS_KEY],
      });
    },
  });
};

export default useGenerateReportMutation;
