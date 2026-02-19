import { useMutation } from "@tanstack/react-query";
import get from "lodash/get";
import api, { RUNNERS_REST_ENDPOINT } from "@/api/api";
import { PairResponse } from "@/types/runners";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";

const useRunnerPairMutation = () => {
  const { toast } = useToast();

  return useMutation({
    mutationFn: async () => {
      const { data } = await api.post<PairResponse>(
        `${RUNNERS_REST_ENDPOINT}pair`,
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
  });
};

export default useRunnerPairMutation;
