import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, {
  PROJECT_STATISTICS_KEY,
  PROJECTS_KEY,
  PROJECTS_REST_ENDPOINT,
} from "@/api/api";
import { Project } from "@/types/projects";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import { extractIdFromLocation } from "@/lib/utils";

type UseProjectCreateMutationParams = {
  project: Partial<Project>;
};

const useProjectCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ project }: UseProjectCreateMutationParams) => {
      const { headers } = await api.post(PROJECTS_REST_ENDPOINT, {
        ...project,
      });

      // TODO workaround to return just created resource while implementation on BE is not done
      const id = extractIdFromLocation(headers?.location);

      return { id };
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
      queryClient.invalidateQueries({
        queryKey: [PROJECT_STATISTICS_KEY],
      });
      queryClient.invalidateQueries({
        queryKey: [PROJECTS_KEY],
      });
    },
  });
};

export default useProjectCreateMutation;
