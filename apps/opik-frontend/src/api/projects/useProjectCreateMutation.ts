import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { PROJECTS_REST_ENDPOINT } from "@/api/api";
import { Project } from "@/types/projects";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";

type UseProjectCreateMutationParams = {
  project: Partial<Project>;
  workspaceName: string;
};

const useProjectCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      project,
      workspaceName,
    }: UseProjectCreateMutationParams) => {
      const { data } = await api.post(PROJECTS_REST_ENDPOINT, {
        ...project,
        workspace_name: workspaceName,
      });
      return data;
    },
    onMutate: async (params: UseProjectCreateMutationParams) => {
      return {
        queryKey: ["projects", { workspaceName: params.workspaceName }],
      };
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
    onSettled: (data, error, variables, context) => {
      if (context) {
        return queryClient.invalidateQueries({ queryKey: context.queryKey });
      }
    },
  });
};

export default useProjectCreateMutation;
