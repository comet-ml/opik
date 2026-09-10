import { useCallback } from "react";
import useLocalStorageState from "use-local-storage-state";

import { MCP_BANNER_CAMPAIGN_END, MCP_BANNER_DISMISSED_KEY } from "./constants";

/**
 * The campaign ends at the close of its last day, in UTC. A user's own
 * timezone is not worth the ambiguity here: the banner is an announcement, and
 * a few hours either side of midnight changes nothing for anyone.
 */
const isWithinCampaign = () =>
  Date.now() <= Date.parse(`${MCP_BANNER_CAMPAIGN_END}T23:59:59.999Z`);

type UseMcpAnnouncementBannerResult = {
  visible: boolean;
  dismiss: () => void;
};

/**
 * Whether the MCP announcement banner may be on screen, and how to put it away.
 *
 * Every condition here is synchronous, which is what lets the banner render in
 * the first painted frame instead of appearing a tick later and pushing the
 * page down (OPIK-8260 forbids that shift explicitly).
 */
export const useMcpAnnouncementBanner = (): UseMcpAnnouncementBannerResult => {
  const [dismissed, setDismissed] = useLocalStorageState<boolean>(
    MCP_BANNER_DISMISSED_KEY,
    { defaultValue: false },
  );

  const dismiss = useCallback(() => setDismissed(true), [setDismissed]);

  return {
    visible: isWithinCampaign() && !dismissed,
    dismiss,
  };
};
