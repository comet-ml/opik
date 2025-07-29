import api, {
    COMPARE_EXPERIMENTS_KEY,
    TRACE_KEY,
    TRACES_BATCH_FEEDBACK_SCORES_ENDPOINT,
    TRACES_KEY,
} from "@/api/api";
import { useToast } from "@/components/ui/use-toast";
import {
    generateUpdateMutation,
    setExperimentsCompareCache,
    setTraceCache,
    setTracesCache,
} from "@/lib/feedback-scores";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

type UseTracesBatchFeedbackScoresParams = {
    projectId: string;
    traceIds: string[];
    name: string;
    value: number;
    categoryName?: string;
    reason?: string;
};

const useTracesBatchFeedbackScoresMutation = () => {
    const queryClient = useQueryClient();
    const { toast } = useToast();

    return useMutation({
        mutationFn: async ({
            projectId,
            traceIds,
            name,
            value,
            categoryName,
            reason,
        }: UseTracesBatchFeedbackScoresParams) => {
            const body = {
                scores: traceIds.map((id) => ({
                    trace_id: id,
                    project_id: projectId,
                    name,
                    value,
                    ...(categoryName && { category_name: categoryName }),
                    ...(reason && { reason }),
                    source: FEEDBACK_SCORE_TYPE.ui,
                })),
            };

            const { data } = await api.put(TRACES_BATCH_FEEDBACK_SCORES_ENDPOINT, body);
            return data;
        },
        onError: (error: AxiosError) => {
            const message = get(error, ["response", "data", "message"], error.message);
            toast({
                title: "Error",
                description: message,
                variant: "destructive",
            });
        },
        onSuccess: (data, vars) => {
            toast({
                title: "Success",
                description: `Annotated ${vars.traceIds.length} traces`,
            });
        },
        onMutate: async (params: UseTracesBatchFeedbackScoresParams) => {
            const updateMutation = generateUpdateMutation({
                name: params.name,
                category_name: params.categoryName,
                value: params.value,
                source: FEEDBACK_SCORE_TYPE.ui,
                reason: params.reason,
            });

            await Promise.all(
                params.traceIds.map(async (traceId) => {
                    const traceParams = { traceId };
                    await setExperimentsCompareCache(queryClient, traceParams, updateMutation);
                    await setTracesCache(queryClient, traceParams, updateMutation);
                    await setTraceCache(queryClient, traceParams, updateMutation);
                }),
            );
        },
        onSettled: async () => {
            await queryClient.invalidateQueries({ queryKey: [TRACES_KEY] });
            await queryClient.invalidateQueries({ queryKey: ["traces-columns"] });
            await queryClient.invalidateQueries({ queryKey: ["traces-statistic"] });
            await queryClient.invalidateQueries({ queryKey: [TRACE_KEY] });
            await queryClient.invalidateQueries({ queryKey: [COMPARE_EXPERIMENTS_KEY] });
        },
    });
};

export default useTracesBatchFeedbackScoresMutation; 