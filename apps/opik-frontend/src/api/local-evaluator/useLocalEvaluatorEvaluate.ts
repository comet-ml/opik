import { useMutation } from "@tanstack/react-query";
import axios from "axios";
import {
  LocalEvaluationRequest,
  LocalEvaluationResponse,
} from "@/types/local-evaluator";

interface UseLocalEvaluatorEvaluateParams {
  url: string;
  traceId: string;
  request: LocalEvaluationRequest;
}

export const useLocalEvaluatorEvaluate = () => {
  return useMutation<LocalEvaluationResponse, Error, UseLocalEvaluatorEvaluateParams>({
    mutationFn: async ({ url, traceId, request }) => {
      const response = await axios.post<LocalEvaluationResponse>(
        `${url}/api/v1/evaluation/traces/${traceId}`,
        request,
        { timeout: 30000 },
      );
      return response.data;
    },
  });
};

export default useLocalEvaluatorEvaluate;
