import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, { DASHBOARDS_REST_ENDPOINT, DASHBOARDS_KEY } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

export interface CreateDashboardRequest {
  name: string;
  description?: string;
  skipDefaultSection?: boolean;
}

export interface CreateDashboardResponse {
  id: string;
  name: string;
  description?: string;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

type UseDashboardCreateMutationParams = {
  dashboard: CreateDashboardRequest;
};

const useDashboardCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ dashboard }: UseDashboardCreateMutationParams) => {
      const { data } = await api.post<CreateDashboardResponse>(
        DASHBOARDS_REST_ENDPOINT,
        dashboard
      );

      return data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: [DASHBOARDS_KEY],
      });
      
      toast({
        title: "Success",
        description: "Dashboard created successfully",
        variant: "default",
      });
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
  });
};

export default useDashboardCreateMutation; 
