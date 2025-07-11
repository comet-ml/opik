import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, { PANEL_TEMPLATES_REST_ENDPOINT, PANEL_TEMPLATES_KEY } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";
import { ReusablePanelTemplate } from "./usePanelTemplatesById";

export interface CreatePanelTemplateRequest {
  name: string;
  description?: string;
  type: 'PYTHON' | 'CHART' | 'TEXT' | 'METRIC' | 'HTML';
  configuration: any;
  default_layout: {
    x: number;
    y: number;
    w: number;
    h: number;
  };
}

type UsePanelTemplateCreateMutationParams = {
  template: CreatePanelTemplateRequest;
};

const usePanelTemplateCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ template }: UsePanelTemplateCreateMutationParams) => {
      const { data } = await api.post<ReusablePanelTemplate>(PANEL_TEMPLATES_REST_ENDPOINT, template);
      return data;
    },
    onSuccess: (data) => {
      toast({
        description: "Panel template created successfully",
      });
      
      return queryClient.invalidateQueries({
        queryKey: [PANEL_TEMPLATES_KEY],
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

export default usePanelTemplateCreateMutation; 