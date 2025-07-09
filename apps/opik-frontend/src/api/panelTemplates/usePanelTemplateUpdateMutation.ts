import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, { PANEL_TEMPLATES_REST_ENDPOINT, PANEL_TEMPLATES_KEY } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";
import { ReusablePanelTemplate } from "./usePanelTemplatesById";

export interface UpdatePanelTemplateRequest {
  name?: string;
  description?: string;
  type?: 'PYTHON' | 'CHART' | 'TEXT' | 'METRIC' | 'HTML';
  configuration?: any;
  default_layout?: {
    x: number;
    y: number;
    w: number;
    h: number;
  };
}

type UsePanelTemplateUpdateMutationParams = {
  templateId: string;
  template: UpdatePanelTemplateRequest;
};

const usePanelTemplateUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ templateId, template }: UsePanelTemplateUpdateMutationParams) => {
      const { data } = await api.put<ReusablePanelTemplate>(
        `${PANEL_TEMPLATES_REST_ENDPOINT}${templateId}`,
        template
      );
      return data;
    },
    onSuccess: (data) => {
      toast({
        description: "Panel template updated successfully",
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

export default usePanelTemplateUpdateMutation; 