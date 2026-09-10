import { useCallback } from "react";
import { useParams } from "@tanstack/react-router";
import useLocalStorageState from "use-local-storage-state";
import { useFeatureFlagEnabled } from "posthog-js/react";

import {
  useDemoProjectBannerVisibility,
  useIsDemoProjectById,
} from "@/v2/layout/DemoProjectBanner/useDemoProjectBannerVisibility";
import {
  MCP_BANNER_CAMPAIGN_END,
  MCP_BANNER_DISMISSED_KEY,
  MCP_BANNER_FEATURE_FLAG_KEY,
} from "./constants";

/**
 * The campaign ends at the close of its last day, in UTC. A user's own
 * timezone is not worth the ambiguity here: the banner is an announcement, and
 * a few hours either side of midnight changes nothing for anyone.
 */
const isWithinCampaign = () =>
  Date.now() <= Date.parse(`${MCP_BANNER_CAMPAIGN_END}T23:59:59.999Z`);

type UseMcpAnnouncementBannerParams = {
  /**
   * Whether the quota/retention banner holds the slot. Passed in rather than
   * derived so this stays unaware of quota concepts — and because only the
   * layout, which mounts both, can answer it.
   */
  retentionBannerVisible?: boolean;
};

type UseMcpAnnouncementBannerResult = {
  /** Whether to render. Decided synchronously, so the first frame is right. */
  visible: boolean;
  /**
   * Whether this render may be counted as an impression. Stricter than
   * `visible`: the demo verdicts arrive from queries, so a banner can be
   * painted and then withdrawn. Counting on `visible` would put suppressed
   * users into the funnel and burn the session's one impression on them.
   */
  countable: boolean;
  dismiss: () => void;
};

/**
 * Whether the MCP announcement banner may be on screen, and how to put it away.
 *
 * The campaign window and the dismissal are synchronous, which is what lets the
 * banner render in the first painted frame instead of appearing a tick later
 * and pushing the page down (OPIK-8260 forbids that shift explicitly).
 *
 * The rest can only ever take the banner away, never delay it:
 *
 * - The kill switch hides on an explicit `false` only. Unresolved is the normal
 *   first-render state on cloud — PostHog initialises behind a fetch of the
 *   runtime config — and the permanent state in OSS, where it never initialises
 *   at all. A truthiness check here would blank the banner on first paint and
 *   reveal it a tick later, and would hide it from OSS entirely.
 * - The demo and quota banners outrank an announcement. Both verdicts arrive
 *   from queries, so they can remove a painted banner; the demo case costs no
 *   movement because both bars are the same height, and the quota case moves
 *   the page once, only for users who are over their limit.
 */
export const useMcpAnnouncementBanner = ({
  retentionBannerVisible = false,
}: UseMcpAnnouncementBannerParams = {}): UseMcpAnnouncementBannerResult => {
  const [dismissed, setDismissed] = useLocalStorageState<boolean>(
    MCP_BANNER_DISMISSED_KEY,
    { defaultValue: false },
  );

  const killed = useFeatureFlagEnabled(MCP_BANNER_FEATURE_FLAG_KEY) === false;

  const { isBannerVisible: demoBannerVisible, isSettled: demoSettled } =
    useDemoProjectBannerVisibility();

  // The route's own project, not the sticky active one: the question is which
  // project is on screen, and the active project outlives the page you opened it on.
  const routeProjectId = useParams({
    strict: false,
    select: (params) => (params as { projectId?: string }).projectId,
  });
  const { isDemoProject: onDemoProjectPage, isSettled: routeSettled } =
    useIsDemoProjectById(routeProjectId);

  const dismiss = useCallback(() => setDismissed(true), [setDismissed]);

  const visible =
    isWithinCampaign() &&
    !dismissed &&
    !killed &&
    !retentionBannerVisible &&
    !demoBannerVisible &&
    !onDemoProjectPage;

  return {
    visible,
    countable: visible && demoSettled && routeSettled,
    dismiss,
  };
};
