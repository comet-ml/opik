import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { RUNNER_JOBS_KEY, RUNNERS_REST_ENDPOINT } from "@/api/api";
import { CreateJobRequest, RunnerJob } from "@/types/runners";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";

const useCreateRunnerJobMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async (request: CreateJobRequest) => {
      const { data } = await api.post<RunnerJob>(
        `${RUNNERS_REST_ENDPOINT}jobs`,
        request,
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
        queryKey: [RUNNER_JOBS_KEY],
      });
    },
  });
};

export default useCreateRunnerJobMutation;
