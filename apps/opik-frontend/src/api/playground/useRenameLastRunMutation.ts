import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import sortBy from "lodash/sortBy";
import uniq from "lodash/uniq";

import api, { EXPERIMENTS_REST_ENDPOINT } from "@/api/api";
import { buildExperimentName } from "@/lib/experiments";
import { extractErrorMessage } from "@/lib/errors";
import {
  PlaygroundLastRun,
  useApplyLastRunRename,
} from "@/store/PlaygroundStore";
import { useToast } from "@/ui/use-toast";

type UseRenameLastRunMutationParams = {
  lastRun: PlaygroundLastRun;
  name: string;
};

const useRenameLastRunMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const applyLastRunRename = useApplyLastRunRename();

  return useMutation({
    mutationFn: async ({ lastRun, name }: UseRenameLastRunMutationParams) => {
      const results = await Promise.allSettled(
        lastRun.experiments.map(({ id, index }) =>
          api.patch(EXPERIMENTS_REST_ENDPOINT + id, {
            name: buildExperimentName(name, index),
          }),
        ),
      );

      const renamed = lastRun.experiments.filter(
        (_, i) => results[i].status === "fulfilled",
      );
      const errors = results
        .filter(
          (result): result is PromiseRejectedResult =>
            result.status === "rejected",
        )
        .map((result) => result.reason as AxiosError);

      return { renamed, errors };
    },
    onSuccess: ({ renamed, errors }, { lastRun, name }) => {
      applyLastRunRename(
        name,
        renamed.map((e) => e.id),
      );

      const total = lastRun.experiments.length;
      const names = sortBy(renamed, "index")
        .map((e) => buildExperimentName(name, e.index))
        .join(" • ");

      if (!errors.length) {
        toast({
          title: "Run renamed",
          description: `${total} ${
            total === 1 ? "experiment" : "experiments"
          } renamed: ${names}`,
        });
        return;
      }

      const message = uniq(errors.map(extractErrorMessage)).join("; ");
      toast({
        title: renamed.length
          ? "Run partly renamed"
          : "Couldn't rename the run",
        description: renamed.length
          ? `Renamed ${renamed.length} of ${total} experiments: ${names}. The others kept their old name. ${message}`
          : message,
        variant: "destructive",
      });
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ["experiments"] });
      queryClient.invalidateQueries({ queryKey: ["experiment"] });
    },
  });
};

export default useRenameLastRunMutation;
