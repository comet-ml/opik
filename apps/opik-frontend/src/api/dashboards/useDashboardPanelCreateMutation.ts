import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, { DASHBOARDS_REST_ENDPOINT, DASHBOARDS_KEY } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

export interface CreateDashboardPanelRequest {
  name: string;
  type: 'PYTHON' | 'CHART' | 'TEXT' | 'METRIC' | 'HTML';
  configuration: any;
  layout: {
    x: number;
    y: number;
    w: number;
    h: number;
  };
}

export interface CreateDashboardPanelResponse {
  id: string;
  name: string;
  type: 'PYTHON' | 'CHART' | 'TEXT' | 'METRIC' | 'HTML';
  configuration: any;
  layout: {
    x: number;
    y: number;
    w: number;
    h: number;
  };
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

type UseDashboardPanelCreateMutationParams = {
  dashboardId: string;
  sectionId: string;
  panel: CreateDashboardPanelRequest;
};

const useDashboardPanelCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ dashboardId, sectionId, panel }: UseDashboardPanelCreateMutationParams) => {
      const { data } = await api.post<CreateDashboardPanelResponse>(
        `${DASHBOARDS_REST_ENDPOINT}${dashboardId}/sections/${sectionId}/panels`,
        panel
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

export default useDashboardPanelCreateMutation; 
