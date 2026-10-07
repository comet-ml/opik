import React, { useEffect, useMemo } from "react";
import { QueryFunctionContext, useQueries } from "@tanstack/react-query";
import { useRouterState } from "@tanstack/react-router";

import { getExperimentById } from "@/api/datasets/useExperimentById";
import { isExperimentTerminal } from "@/lib/experiments";
import { EXPERIMENT_STATUS, Experiment } from "@/types/datasets";
import {
  useExperimentByPromptId,
  useHasUnseenRunCompletion,
  useIsRunInFlight,
  useSetHasUnseenRunCompletion,
  useSettleRun,
} from "@/store/PlaygroundStore";

const POLL_INTERVAL_MS = 10000;

const MAX_WATCH_AGE_MS = 60 * 60 * 1000;

const watchedTooLong = (experiment: Experiment | undefined) => {
  if (!experiment?.created_at) return false;
  return (
    Date.now() - new Date(experiment.created_at).getTime() > MAX_WATCH_AGE_MS
  );
};

type PlaygroundNavBadgeProps = {
  collapsed: boolean;
};

/**
 * Tells the user a playground run finished while they were looking at something else.
 *
 * The run outlives the page — that is the point of executing it server-side — but the page's own
 * polling stops the moment it unmounts, so nothing else is in a position to notice. This watches
 * from the sidebar instead, and only while a run is actually in flight and the playground is
 * elsewhere: experiment ids outlive the run that made them, and watching those would mean polling
 * for ever on behalf of a run that ended long ago.
 */
const PlaygroundNavBadge: React.FC<PlaygroundNavBadgeProps> = ({
  collapsed,
}) => {
  const experimentByPromptId = useExperimentByPromptId();
  const hasUnseenRunCompletion = useHasUnseenRunCompletion();
  const setHasUnseenRunCompletion = useSetHasUnseenRunCompletion();
  const isRunInFlight = useIsRunInFlight();
  const settleRun = useSettleRun();

  const isOnPlayground = useRouterState({
    select: (state) => state.location.pathname.endsWith("/playground"),
  });

  const experimentIds = useMemo(
    () => Object.values(experimentByPromptId ?? {}),
    [experimentByPromptId],
  );

  const shouldWatch =
    isRunInFlight && !isOnPlayground && experimentIds.length > 0;

  const results = useQueries({
    queries: experimentIds.map((experimentId) => ({
      queryKey: ["experiment", { experimentId }],
      queryFn: (context: QueryFunctionContext) =>
        getExperimentById(context, { experimentId }),
      enabled: shouldWatch,
      refetchInterval: (query: { state: { data?: Experiment } }) =>
        watchedTooLong(query.state.data) ? false : POLL_INTERVAL_MS,
    })),
  });

  const statuses = results.map((result) => result.data?.status);
  const allLoaded = statuses.every((status) => status !== undefined);
  const allTerminal =
    allLoaded &&
    statuses.every((status) =>
      isExperimentTerminal(status as EXPERIMENT_STATUS | undefined),
    );

  useEffect(() => {
    if (shouldWatch && allLoaded && allTerminal) {
      settleRun();
      setHasUnseenRunCompletion(true);
    }
  }, [
    shouldWatch,
    allLoaded,
    allTerminal,
    setHasUnseenRunCompletion,
    settleRun,
  ]);

  useEffect(() => {
    if (isOnPlayground) {
      setHasUnseenRunCompletion(false);
    }
  }, [isOnPlayground, setHasUnseenRunCompletion]);

  if (!hasUnseenRunCompletion) return null;

  const dot = <span className="size-1.5 rounded-full bg-primary" />;

  return collapsed ? (
    <span className="absolute right-0.5 top-0.5 flex items-center justify-center">
      {dot}
    </span>
  ) : (
    <span className="ml-auto flex shrink-0 items-center justify-center pl-1">
      {dot}
    </span>
  );
};

export default PlaygroundNavBadge;
