import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, { PANEL_TEMPLATES_REST_ENDPOINT, PANEL_TEMPLATES_KEY } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

type UsePanelTemplateDeleteMutationParams = {
  templateId: string;
};

const usePanelTemplateDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ templateId }: UsePanelTemplateDeleteMutationParams) => {
      await api.delete(`${PANEL_TEMPLATES_REST_ENDPOINT}${templateId}`);
    },
    onSuccess: () => {
      toast({
        description: "Panel template deleted successfully",
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

export default usePanelTemplateDeleteMutation; 