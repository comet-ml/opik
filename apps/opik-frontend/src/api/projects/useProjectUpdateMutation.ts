import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { PROJECTS_KEY, PROJECTS_REST_ENDPOINT } from "@/api/api";
import { Project } from "@/types/projects";
import { useToast } from "@/components/ui/use-toast";

type UseProjectUpdateMutationParams = {
  project: Partial<Project>;
};

const useProjectUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ project }: UseProjectUpdateMutationParams) => {
      const { data } = await api.patch(
        PROJECTS_REST_ENDPOINT + project.id,
        project,
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
    onSettled: () => {
      return queryClient.invalidateQueries({
        queryKey: [PROJECTS_KEY],
      });
    },
  });
};

export default useProjectUpdateMutation;
