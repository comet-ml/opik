import React, { useCallback, useLayoutEffect, useRef } from "react";
import { ArrowUpRight, Plug, X } from "lucide-react";

import useAppStore from "@/store/AppStore";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import { useIsPhone } from "@/hooks/useIsPhone";
import { buildDocsUrl } from "@/lib/utils";
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
  MCP_BANNER_SHOWN_SESSION_KEY,
  McpBannerCopyVariant,
} from "./constants";
import { useMcpAnnouncementBanner } from "./useMcpAnnouncementBanner";

interface McpAnnouncementBannerProps {
  onChangeHeight: (height: number) => void;
  retentionBannerVisible: boolean;
}

// One impression per browser session; a reload must not add another.
const claimSessionImpression = (): boolean => {
  try {
    if (window.sessionStorage.getItem(MCP_BANNER_SHOWN_SESSION_KEY)) {
      return false;
    }
    window.sessionStorage.setItem(MCP_BANNER_SHOWN_SESSION_KEY, "1");
    return true;
  } catch {
    return true;
  }
};

// Read at call time: the workspace name is reported, never rendered.
const bannerEventProperties = (copyVariant: McpBannerCopyVariant) => ({
  workspace_name: useAppStore.getState().activeWorkspaceName,
  campaign_id: MCP_BANNER_CAMPAIGN_ID,
  copy_variant: copyVariant,
});

const McpAnnouncementBanner: React.FC<McpAnnouncementBannerProps> = ({
  onChangeHeight,
  retentionBannerVisible,
}) => {
  const heightRef = useRef(MCP_BANNER_HEIGHT);
  const { visible, countable, dismiss } = useMcpAnnouncementBanner({
    retentionBannerVisible,
  });
  const { isPhone } = useIsPhone();

  const copy = isPhone ? MCP_BANNER_COPY_SHORT : MCP_BANNER_COPY;
  const copyVariant = isPhone
    ? MCP_BANNER_COPY_VARIANT.SHORT
    : MCP_BANNER_COPY_VARIANT.FULL;

  const { ref } = useObserveResizeNode<HTMLDivElement>((node) => {
    heightRef.current = node.clientHeight;
    onChangeHeight(node.clientHeight);
  });

  // Before paint, with the known height: the layout animates its offset, so
  // publishing a measured height a frame late reads as the page sliding down.
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
      className="z-10 flex h-8 items-center gap-1.5 bg-[linear-gradient(-1.2deg,var(--mcp-banner-gradient-start)_0%,var(--mcp-banner-gradient-end)_68.5%)] px-2"
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
          // Explicit rgba: `white` is a bare var(--white) with no <alpha-value>,
          // so bg-white/15 is never generated.
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
