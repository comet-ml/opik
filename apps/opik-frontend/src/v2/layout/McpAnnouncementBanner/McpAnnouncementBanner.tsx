import React, { useCallback, useLayoutEffect, useRef } from "react";
import { ArrowUpRight, Plug, X } from "lucide-react";

import useAppStore from "@/store/AppStore";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import { useIsPhone } from "@/hooks/useIsPhone";
import { buildDocsUrl, cn } from "@/lib/utils";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import {
  MCP_BANNER_CAMPAIGN_ID,
  MCP_BANNER_COPY,
  MCP_BANNER_COPY_SHORT,
  MCP_BANNER_COPY_VARIANT,
  MCP_BANNER_DOCS_PATH,
  MCP_BANNER_HEIGHT,
  MCP_BANNER_HEIGHT_CLASS,
  MCP_BANNER_SHOWN_SESSION_KEY,
  McpBannerCopyVariant,
} from "./constants";
import { useMcpAnnouncementBanner } from "./useMcpAnnouncementBanner";

interface McpAnnouncementBannerProps {
  onChangeHeight: (height: number) => void;
  /** The quota banner outranks an announcement; only the layout knows it is up. */
  retentionBannerVisible: boolean;
  /** And whether that answer is final; it arrives from a query. */
  retentionBannerSettled: boolean;
}

/**
 * Take this session's one impression, if it is still going. Session storage
 * rather than a ref: the layout survives navigation but not a reload, and a
 * reload should not put a second impression into the funnel's denominator.
 */
const claimSessionImpression = (): boolean => {
  try {
    if (window.sessionStorage.getItem(MCP_BANNER_SHOWN_SESSION_KEY)) {
      return false;
    }
    window.sessionStorage.setItem(MCP_BANNER_SHOWN_SESSION_KEY, "1");
    return true;
  } catch {
    // Private modes and blocked site data throw on access. Counting the
    // impression is worth more than de-duplicating it.
    return true;
  }
};

/**
 * Read at call time rather than through a subscription: the workspace name is
 * never rendered here, only reported, so subscribing would re-render the bar
 * on every store change for a value nobody sees.
 */
const bannerEventProperties = (copyVariant: McpBannerCopyVariant) => ({
  workspace_name: useAppStore.getState().activeWorkspaceName,
  campaign_id: MCP_BANNER_CAMPAIGN_ID,
  copy_variant: copyVariant,
});

const McpAnnouncementBanner: React.FC<McpAnnouncementBannerProps> = ({
  onChangeHeight,
  retentionBannerVisible,
  retentionBannerSettled,
}) => {
  const heightRef = useRef(MCP_BANNER_HEIGHT);
  const { visible, countable, dismiss } = useMcpAnnouncementBanner({
    retentionBannerVisible,
    retentionBannerSettled,
  });
  // Either orientation: a phone in landscape has no more room for an 88
  // character line than one held upright.
  const { isPhone } = useIsPhone();

  const copy = isPhone ? MCP_BANNER_COPY_SHORT : MCP_BANNER_COPY;
  const copyVariant = isPhone
    ? MCP_BANNER_COPY_VARIANT.SHORT
    : MCP_BANNER_COPY_VARIANT.FULL;

  const { ref } = useObserveResizeNode<HTMLDivElement>((node) => {
    heightRef.current = node.clientHeight;
    onChangeHeight(node.clientHeight);
  });

  // Layout effect, and the height known from the design rather than the
  // measured one: the layout animates its content offset, so publishing the
  // height a frame late reads as the whole page sliding down on every load.
  useLayoutEffect(() => {
    onChangeHeight(visible ? heightRef.current : 0);
  }, [visible, onChangeHeight]);

  useLayoutEffect(() => {
    if (!countable || !claimSessionImpression()) return;

    trackEvent(OpikEvent.MCP_BANNER_SHOWN, bannerEventProperties(copyVariant));
  }, [countable, copyVariant]);

  const handleCtaClick = useCallback(() => {
    trackEvent(
      OpikEvent.MCP_BANNER_CTA_CLICKED,
      bannerEventProperties(copyVariant),
    );
  }, [copyVariant]);

  const handleDismiss = useCallback(() => {
    trackEvent(
      OpikEvent.MCP_BANNER_DISMISSED,
      bannerEventProperties(copyVariant),
    );
    dismiss();
  }, [dismiss, copyVariant]);

  if (!visible) {
    return null;
  }

  return (
    <div
      ref={ref}
      role="region"
      aria-label="Opik MCP announcement"
      className={cn(
        "z-10 flex items-center gap-1.5 bg-[linear-gradient(-1.2deg,var(--mcp-banner-gradient-start)_0%,var(--mcp-banner-gradient-end)_68.5%)] px-2",
        MCP_BANNER_HEIGHT_CLASS,
      )}
    >
      <div className="flex min-w-0 flex-1 items-center justify-center gap-1.5">
        <Plug className="size-3.5 shrink-0 text-white" />
        <span className="comet-body-xs min-w-0 truncate text-white">
          {copy}
        </span>
        <Button
          variant="link"
          size="2xs"
          asChild
          // The underline stays on hover: it reads as the link's identity here,
          // not as its hover state. The affordance is a faint white veil, given
          // as an explicit rgba because `white` is configured as a bare
          // `var(--white)` with no <alpha-value>, so Tailwind emits no rule for
          // bg-white/15 and friends.
          className="shrink-0 rounded text-white underline underline-offset-2 hover:bg-[rgba(255,255,255,0.18)] hover:text-white hover:underline focus-visible:ring-white active:bg-[rgba(255,255,255,0.28)]"
        >
          <a
            href={buildDocsUrl(MCP_BANNER_DOCS_PATH)}
            target="_blank"
            rel="noreferrer"
            onClick={handleCtaClick}
          >
            <span>Learn more</span>
            <ArrowUpRight className="ml-0.5 size-3 shrink-0" />
          </a>
        </Button>
      </div>
      <TooltipWrapper content="Dismiss">
        <Button
          variant="ghostInverted"
          size="icon-2xs"
          aria-label="Dismiss announcement"
          onClick={handleDismiss}
          className="shrink-0 text-white hover:bg-[rgba(255,255,255,0.18)] focus-visible:ring-white active:bg-[rgba(255,255,255,0.28)]"
        >
          <X />
        </Button>
      </TooltipWrapper>
    </div>
  );
};

export default McpAnnouncementBanner;
