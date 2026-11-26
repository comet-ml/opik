import { useCallback } from "react";
import { AxiosError } from "axios";
import useModelPrices from "@/api/model-prices/useModelPrices";
import useModelPricesReplaceMutation from "@/api/model-prices/useModelPricesReplaceMutation";
import { useToast } from "@/ui/use-toast";
import {
  ModelPriceProblem,
  ModelPriceReplace,
  ModelPriceReplaceResult,
  SAVE_ERROR_MESSAGES,
  classifySaveError,
} from "@/lib/model-prices";

export type SaveOutcome =
  | { kind: "previewed"; result: ModelPriceReplaceResult }
  | { kind: "saved"; result: ModelPriceReplaceResult }
  | {
      kind: "invalid";
      errors: ModelPriceProblem[];
      warnings: ModelPriceProblem[];
    }
  | { kind: "conflict" }
  | { kind: "failed" };

const useSaveModelPrices = () => {
  const { toast } = useToast();
  const { refetch } = useModelPrices();
  const { mutateAsync, isPending } = useModelPricesReplaceMutation();

  const send = useCallback(
    async (
      request: ModelPriceReplace,
      dryRun: boolean,
      savedNote?: string,
    ): Promise<SaveOutcome> => {
      try {
        const result = await mutateAsync({ request, dryRun });
        if (result.saved) {
          const saved = `Prices saved as version ${result.version}`;
          toast(
            savedNote
              ? { title: saved, description: savedNote }
              : { description: saved },
          );
          return { kind: "saved", result };
        }
        return { kind: "previewed", result };
      } catch (error) {
        const response = (error as AxiosError).response;
        const failure = classifySaveError(response?.status, response?.data);
        if (failure.kind === "invalid") {
          return { kind: "invalid", ...failure.problems };
        }
        if (failure.kind === "conflict") {
          await refetch();
        }
        toast({
          title: "Prices not saved",
          description:
            failure.kind === "unknown"
              ? (failure.message ?? SAVE_ERROR_MESSAGES.unknown)
              : SAVE_ERROR_MESSAGES[failure.kind],
          variant: "destructive",
        });
        return failure.kind === "conflict"
          ? { kind: "conflict" }
          : { kind: "failed" };
      }
    },
    [mutateAsync, refetch, toast],
  );

  return { send, isSaving: isPending };
};

export default useSaveModelPrices;
