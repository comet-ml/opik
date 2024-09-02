import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, { PROJECTS_REST_ENDPOINT } from "@/api/api";
import { UseProjectsListResponse } from "@/api/projects/useProjectsList";

type UseProjectDeleteMutationParams = {
  projectId: string;
  workspaceName: string;
};

const useProjectDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ projectId }: UseProjectDeleteMutationParams) => {
      const { data } = await api.delete(PROJECTS_REST_ENDPOINT + projectId);
      return data;
    },
    onMutate: async (params: UseProjectDeleteMutationParams) => {
      const queryKey = ["projects", { workspaceName: params.workspaceName }];

      await queryClient.cancelQueries({ queryKey });
      const previousProjects: UseProjectsListResponse | undefined =
        queryClient.getQueryData(queryKey);
      if (previousProjects) {
        queryClient.setQueryData(queryKey, () => {
          return {
            ...previousProjects,
            content: previousProjects.content.filter(
              (p) => p.id !== params.projectId,
            ),
          };
        });
      }

      return { previousProjects, queryKey };
    },
    onError: (error, data, context) => {
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

      if (context) {
        queryClient.setQueryData(context.queryKey, context.previousProjects);
      }
    },
    onSettled: (data, error, variables, context) => {
      if (context) {
        return queryClient.invalidateQueries({ queryKey: context.queryKey });
      }
    },
  });
};

export default useProjectDeleteMutation;
