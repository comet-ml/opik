import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import api, {
  MODEL_PRICE_HISTORY_KEY,
  MODEL_PRICES_KEY,
  MODEL_PRICES_REST_ENDPOINT,
} from "@/api/api";
import { ModelPriceReplace, ModelPriceReplaceResult } from "@/lib/model-prices";

type UseModelPricesReplaceMutationParams = {
  request: ModelPriceReplace;
  dryRun: boolean;
};

const useModelPricesReplaceMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      request,
      dryRun,
    }: UseModelPricesReplaceMutationParams) => {
      const { data } = await api.put<ModelPriceReplaceResult>(
        MODEL_PRICES_REST_ENDPOINT,
        request,
        { params: { dry_run: dryRun } },
      );
      return data;
    },
    onSuccess: (result) => {
      if (!result.saved) return;
      queryClient.invalidateQueries({ queryKey: [MODEL_PRICES_KEY] });
      queryClient.invalidateQueries({ queryKey: [MODEL_PRICE_HISTORY_KEY] });
    },
    onError: (error) => {
      if ((error as AxiosError).response?.status !== 409) return;
      queryClient.invalidateQueries({ queryKey: [MODEL_PRICE_HISTORY_KEY] });
    },
  });
};

export default useModelPricesReplaceMutation;
