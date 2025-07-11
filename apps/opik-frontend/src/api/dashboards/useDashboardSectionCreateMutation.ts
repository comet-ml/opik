import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, { DASHBOARDS_REST_ENDPOINT, DASHBOARDS_KEY } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

export interface CreateDashboardSectionRequest {
  title: string;
  position_order?: number;
}

export interface CreateDashboardSectionResponse {
  id: string;
  title: string;
  position_order: number;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

type UseDashboardSectionCreateMutationParams = {
  dashboardId: string;
  section: CreateDashboardSectionRequest;
};

const useDashboardSectionCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ dashboardId, section }: UseDashboardSectionCreateMutationParams) => {
      const { data } = await api.post<CreateDashboardSectionResponse>(
        `${DASHBOARDS_REST_ENDPOINT}${dashboardId}/sections`,
        section
      );

      return data;
    },
    onError: (error: AxiosError) => {
      const message = get(
        error,
        ["response", "data", "message"],
        error.message,
      );

      toast({
        title: "Error",
        description: message,
        variant: "destructive",
      });
    },
    onSettled: (_, __, { dashboardId }) => {
      queryClient.invalidateQueries({
        queryKey: [DASHBOARDS_KEY, { dashboardId }],
      });
    },
  });
};

export default useDashboardSectionCreateMutation; 
