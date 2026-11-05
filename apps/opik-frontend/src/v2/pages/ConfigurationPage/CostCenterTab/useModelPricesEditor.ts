import { useCallback, useEffect, useMemo, useReducer } from "react";
import { AxiosError } from "axios";
import useModelPrices from "@/api/model-prices/useModelPrices";
import useModelPricesReplaceMutation from "@/api/model-prices/useModelPricesReplaceMutation";
import { useToast } from "@/ui/use-toast";
import {
  EMPTY_DRAFT,
  ModelPriceReplace,
  ModelPriceReplaceResult,
  SAVE_ERROR_MESSAGES,
  changeCounts,
  classifySaveError,
  hasChanges,
  modelPricesReducer,
  toReplaceRequest,
  validateDraft,
} from "@/lib/model-prices";

const useModelPricesEditor = () => {
  const { toast } = useToast();
  const { data, isPending, isError, refetch } = useModelPrices();
  const { mutateAsync, isPending: isSaving } = useModelPricesReplaceMutation();
  const [draft, dispatch] = useReducer(modelPricesReducer, EMPTY_DRAFT);

  useEffect(() => {
    if (data) {
      dispatch({ type: "loaded", list: data });
    }
  }, [data]);

  const clientErrors = useMemo(() => validateDraft(draft), [draft]);
  const counts = useMemo(() => changeCounts(draft), [draft]);
  const isDirty = useMemo(() => hasChanges(draft), [draft]);
  const hasClientErrors = Object.keys(clientErrors).length > 0;

  const handleSaveError = useCallback(
    async (error: unknown, keys: string[]) => {
      const response = (error as AxiosError).response;
      const failure = classifySaveError(response?.status, response?.data);

      if (failure.kind === "invalid") {
        dispatch({
          type: "problemsReceived",
          keys,
          errors: failure.problems.errors,
          warnings: failure.problems.warnings,
        });
      }
      if (failure.kind === "conflict") {
        await refetch();
      }
      toast({
        title: "Prices not saved",
        description:
          failure.kind === "unknown"
            ? failure.message ?? SAVE_ERROR_MESSAGES.unknown
            : SAVE_ERROR_MESSAGES[failure.kind],
        variant: "destructive",
      });
    },
    [refetch, toast],
  );

  const run = useCallback(
    async (
      dryRun: boolean,
    ): Promise<
      | { request: ModelPriceReplace; result: ModelPriceReplaceResult }
      | undefined
    > => {
      const { request, keys } = toReplaceRequest(draft);
      try {
        const result = await mutateAsync({ request, dryRun });
        if (result.saved) {
          // Load the saved list before the draft forgets its edits, so rows just added aren't shown twice.
          const { data: saved } = await refetch();
          if (saved) dispatch({ type: "reset", list: saved });
          toast({ description: `Prices saved as version ${result.version}` });
        }
        return { request, result };
      } catch (error) {
        await handleSaveError(error, keys);
        return undefined;
      }
    },
    [draft, mutateAsync, refetch, handleSaveError, toast],
  );

  return {
    draft,
    dispatch,
    clientErrors,
    counts,
    isDirty,
    hasClientErrors,
    isPending,
    isError,
    isSaving,
    preview: useCallback(() => run(true), [run]),
    save: useCallback(() => run(false), [run]),
  };
};

export default useModelPricesEditor;
