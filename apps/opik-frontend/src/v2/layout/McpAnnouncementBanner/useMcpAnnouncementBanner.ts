import { useCallback } from "react";
import useLocalStorageState from "use-local-storage-state";
import { useFeatureFlagEnabled } from "posthog-js/react";

import { useDemoProjectBannerVisibility } from "@/v2/layout/DemoProjectBanner/useDemoProjectBannerVisibility";
import {
  MCP_BANNER_CAMPAIGN_END,
  MCP_BANNER_DISMISSED_KEY,
  MCP_BANNER_FEATURE_FLAG_KEY,
} from "./constants";

// Inclusive last day, UTC.
const isWithinCampaign = () =>
  Date.now() <= Date.parse(`${MCP_BANNER_CAMPAIGN_END}T23:59:59.999Z`);

type UseMcpAnnouncementBannerParams = {
  retentionBannerVisible: boolean;
};

type UseMcpAnnouncementBannerResult = {
  visible: boolean;
  // Impression gate: the demo verdict comes from a query, so a painted banner
  // can still be withdrawn. Do not count it until that verdict has settled.
  countable: boolean;
  dismiss: () => void;
};

export const useMcpAnnouncementBanner = ({
  retentionBannerVisible,
}: UseMcpAnnouncementBannerParams): UseMcpAnnouncementBannerResult => {
  const [dismissed, setDismissed] = useLocalStorageState<boolean>(
    MCP_BANNER_DISMISSED_KEY,
    { defaultValue: false },
  );

  // Hide-only: `undefined` (flags not loaded, or no PostHog in OSS) must show,
  // otherwise the banner appears a tick late on cloud and never in OSS.
  const killed = useFeatureFlagEnabled(MCP_BANNER_FEATURE_FLAG_KEY) === false;

  const {
    isBannerVisible: demoBannerVisible,
    isOnDemoProjectPage,
    isSettled: demoSettled,
  } = useDemoProjectBannerVisibility();

  const dismiss = useCallback(() => setDismissed(true), [setDismissed]);

  const visible =
    isWithinCampaign() &&
    !dismissed &&
    !killed &&
    !retentionBannerVisible &&
    !demoBannerVisible &&
    !isOnDemoProjectPage;

  return { visible, countable: visible && demoSettled, dismiss };
};
