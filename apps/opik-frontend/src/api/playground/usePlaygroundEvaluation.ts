import { useMutation } from "@tanstack/react-query";
import { useToast } from "@/components/ui/use-toast";
import api, { PLAYGROUND_EVALUATION_REST_ENDPOINT } from "@/api/api";
import { AxiosError } from "axios";

export interface PlaygroundEvaluationRequest {
  datasetId: string;
  messages: Array<{
    role: string;
    content: string;
  }>;
  model: string;
  modelConfig?: Record<string, unknown>;
  evaluationMetrics: string[];
  experimentName?: string;
  experimentConfig?: Record<string, unknown>;
}

export interface MetricSummary {
  averageScore?: number;
  passCount?: number;
  totalCount: number;
}

export interface PlaygroundEvaluationResponse {
  experimentId: string;
  experimentName: string;
  totalItems: number;
  metricsSummary: Record<string, MetricSummary>;
  durationMs: number;
}

interface UsePlaygroundEvaluationArgs {
  workspaceName: string;
}

const usePlaygroundEvaluation = ({
  workspaceName,
}: UsePlaygroundEvaluationArgs) => {
  const { toast } = useToast();

  return useMutation({
    mutationFn: async (
      request: PlaygroundEvaluationRequest,
    ): Promise<PlaygroundEvaluationResponse> => {
      const { data } = await api.post(
        PLAYGROUND_EVALUATION_REST_ENDPOINT,
        request,
        {
          headers: {
            "Comet-Workspace": workspaceName,
          },
        },
      );
      return data;
    },
    onError: (error: AxiosError) => {
      const message =
        error?.response?.status === 429
          ? "Too many requests. Please try again later."
          : "Failed to run evaluation. Please try again.";

      toast({
        title: "Evaluation Failed",
        description: message,
        variant: "destructive",
      });
    },
    onSuccess: (data) => {
      toast({
        title: "Evaluation Completed",
        description: `Processed ${data.totalItems} items in ${(
          data.durationMs / 1000
        ).toFixed(1)}s`,
      });
    },
  });
};

export default usePlaygroundEvaluation;
