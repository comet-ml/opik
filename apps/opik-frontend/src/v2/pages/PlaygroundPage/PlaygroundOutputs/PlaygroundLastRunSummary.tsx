import React, { useState } from "react";
import { Link } from "@tanstack/react-router";
import { ArrowUpRight, X } from "lucide-react";

import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { getAlphabetLetter } from "@/lib/utils";
import { useLastRun } from "@/store/PlaygroundStore";
import useLastRunExperiments from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/useLastRunExperiments";

const EXPERIMENTS_COMPARE_ROUTE =
  "/$workspaceName/projects/$projectId/experiments/$datasetId/compare";

type PlaygroundLastRunSummaryProps = {
  datasetId?: string;
};

const PlaygroundLastRunSummary = ({
  datasetId,
}: PlaygroundLastRunSummaryProps) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const projectId = useActiveProjectId();
  const lastRun = useLastRun(datasetId);
  const experiments = useLastRunExperiments(lastRun);
  const [dismissedRunKey, setDismissedRunKey] = useState<string | null>(null);

  const runKey = lastRun?.experiments.map((e) => e.id).join() ?? null;

  if (
    !lastRun ||
    !datasetId ||
    !projectId ||
    experiments.length < 2 ||
    dismissedRunKey === runKey
  ) {
    return null;
  }

  const params = { workspaceName, projectId, datasetId };

  return (
    <div
      className="border-t bg-background px-4 pb-1 pt-2"
      data-testid="playground-last-run-summary"
    >
      <div className="flex items-center gap-3">
        <span className="comet-body-s text-foreground">
          Last run complete
          {lastRun.name && (
            <>
              : <span className="comet-body-s-accented">{lastRun.name}</span>
            </>
          )}
        </span>
        <Link
          to={EXPERIMENTS_COMPARE_ROUTE}
          params={params}
          search={{ experiments: experiments.map((e) => e.id) }}
          className="comet-body-s inline-flex items-center gap-0.5 text-muted-slate underline underline-offset-4 hover:text-foreground"
        >
          Compare results
          <ArrowUpRight className="size-3.5 shrink-0" />
        </Link>
        <TooltipWrapper content="Dismiss">
          <Button
            variant="ghost"
            size="icon-xs"
            className="ml-auto"
            aria-label="Dismiss last run summary"
            onClick={() => setDismissedRunKey(runKey)}
          >
            <X />
          </Button>
        </TooltipWrapper>
      </div>
      <ul className="divide-y">
        {experiments.map((experiment) => (
          <li key={experiment.id} className="py-1.5">
            <Link
              to={EXPERIMENTS_COMPARE_ROUTE}
              params={params}
              search={{ experiments: [experiment.id] }}
              className="comet-body-s inline-flex items-center gap-0.5 text-foreground underline underline-offset-4 hover:text-primary"
            >
              {experiment.name ??
                `Prompt ${getAlphabetLetter(experiment.index)} experiment`}
              <ArrowUpRight className="size-3.5 shrink-0" />
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
};

export default PlaygroundLastRunSummary;
