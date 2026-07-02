import { useCallback, useMemo } from "react";
import isArray from "lodash/isArray";
import isString from "lodash/isString";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParams,
} from "use-query-params";

export type TrialSidebarTab = "results" | "prompt";

export type OpenTrialTarget = {
  experimentIds: string[];
  trialNumber: number;
};

const PARAM_CONFIG = {
  trials: JsonParam,
  trialNumber: NumberParam,
  trialTab: StringParam,
  // Owned by the embedded test-items table (TrialItemsTab); registered here so
  // opening another trial resets them and closing clears them — otherwise they
  // go stale on the run URL. `size`/`height` mirror localStorage, so clearing
  // them loses nothing.
  trace: StringParam,
  span: StringParam,
  page: NumberParam,
  size: NumberParam,
  height: StringParam,
  filters: JsonParam,
};

// Per-trial state: switching trials must not carry over the previous trial's
// pagination, filters, or open trace.
const PER_TRIAL_PARAMS = {
  trace: undefined,
  span: undefined,
  page: undefined,
  filters: undefined,
};

const CLOSED_PARAMS = {
  ...PER_TRIAL_PARAMS,
  trials: undefined,
  trialNumber: undefined,
  trialTab: undefined,
  size: undefined,
  height: undefined,
};

/**
 * URL state for the trial sidebar over the run overview. `trials` (the trial's
 * experiment ids) and `trialNumber` keep the exact names the old /trials route
 * used, so old deep links redirect 1:1. A non-empty `trials` array means the
 * sidebar is open. All updates are batched into a single `replaceIn` history
 * entry, matching every other side panel in the app.
 */
export const useTrialSidebarState = () => {
  const [query, setQuery] = useQueryParams(PARAM_CONFIG);

  const experimentIds: string[] = useMemo(
    () => (isArray(query.trials) ? query.trials.filter(isString) : []),
    [query.trials],
  );

  const tab: TrialSidebarTab =
    query.trialTab === "prompt" ? "prompt" : "results";

  const setTab = useCallback(
    (nextTab: string) =>
      setQuery(
        { trialTab: nextTab === "prompt" ? "prompt" : undefined },
        "replaceIn",
      ),
    [setQuery],
  );

  const openTrial = useCallback(
    (target: OpenTrialTarget, targetTab?: TrialSidebarTab) =>
      setQuery(
        {
          ...PER_TRIAL_PARAMS,
          trials: target.experimentIds,
          trialNumber: target.trialNumber,
          trialTab: targetTab === "prompt" ? "prompt" : undefined,
        },
        "replaceIn",
      ),
    [setQuery],
  );

  const close = useCallback(
    () => setQuery(CLOSED_PARAMS, "replaceIn"),
    [setQuery],
  );

  return {
    open: experimentIds.length > 0,
    experimentIds,
    trialNumber: query.trialNumber ?? undefined,
    tab,
    setTab,
    openTrial,
    close,
  };
};
