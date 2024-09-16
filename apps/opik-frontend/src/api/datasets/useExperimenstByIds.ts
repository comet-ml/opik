import { QueryFunctionContext, useQueries } from "@tanstack/react-query";
import {
  getExperimentById,
  UseExperimentByIdParams,
} from "@/api/datasets/useExperimentById";

type UseExperimentsByIdsParams = {
  experimentsIds: string[];
};

export default function useExperimentsByIds(params: UseExperimentsByIdsParams) {
  return useQueries({
    queries: params.experimentsIds.map((experimentId) => {
      const p: UseExperimentByIdParams = {
        experimentId,
      };

      return {
        queryKey: ["experiment", p],
        queryFn: (context: QueryFunctionContext) =>
          getExperimentById(context, p),
      };
    }),
  });
}
