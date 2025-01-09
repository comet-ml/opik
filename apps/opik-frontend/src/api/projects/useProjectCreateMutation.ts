import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { PROJECTS_REST_ENDPOINT } from "@/api/api";
import { Project } from "@/types/projects";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import { last } from "lodash";

type UseProjectCreateMutationParams = {
  project: Partial<Project>;
};

const useProjectCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ project }: UseProjectCreateMutationParams) => {
      const res = await api.post(PROJECTS_REST_ENDPOINT, {
        ...project,
      });

      return { ...res.data, id: last(res.headers?.location?.split("/")) };
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
        queryKey: ["projects"],
      });
    },
  });
};

export default useProjectCreateMutation;
