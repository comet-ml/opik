import React, { useCallback, useEffect, useRef } from "react";
import { ArrowUpRight, Plug, X } from "lucide-react";

import { useActiveWorkspaceName } from "@/store/AppStore";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import { useIsPhone } from "@/hooks/useIsPhone";
import { buildDocsUrl } from "@/lib/utils";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import { Button } from "@/ui/button";
import {
  MCP_BANNER_CAMPAIGN_ID,
  MCP_BANNER_COPY,
  MCP_BANNER_COPY_SHORT,
  MCP_BANNER_COPY_VARIANT,
  MCP_BANNER_DOCS_PATH,
  MCP_BANNER_SHOWN_SESSION_KEY,
} from "./constants";
import { useMcpAnnouncementBanner } from "./useMcpAnnouncementBanner";

interface McpAnnouncementBannerProps {
  onChangeHeight: (height: number) => void;
  /** The quota banner outranks an announcement; only the layout knows it is up. */
  retentionBannerVisible?: boolean;
}

/**
 * Has this session already been counted? Session storage rather than a ref:
 * the layout survives navigation but not a reload, and a reload should not
 * put a second impression into the funnel's denominator.
 */
const markSessionImpression = (): boolean => {
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

const McpAnnouncementBanner: React.FC<McpAnnouncementBannerProps> = ({
  onChangeHeight,
  retentionBannerVisible = false,
}) => {
  const heightRef = useRef(0);
  const workspaceName = useActiveWorkspaceName();
  const { visible, dismiss } = useMcpAnnouncementBanner({
    retentionBannerVisible,
  });
  const { isPhonePortrait } = useIsPhone();

  const copy = isPhonePortrait ? MCP_BANNER_COPY_SHORT : MCP_BANNER_COPY;
  const copyVariant = isPhonePortrait
    ? MCP_BANNER_COPY_VARIANT.SHORT
    : MCP_BANNER_COPY_VARIANT.FULL;

  const { ref } = useObserveResizeNode<HTMLDivElement>((node) => {
    heightRef.current = node.clientHeight;
    onChangeHeight(node.clientHeight);
  });

  const eventProperties = {
    workspace_name: workspaceName,
    campaign_id: MCP_BANNER_CAMPAIGN_ID,
    copy_variant: copyVariant,
  };

  useEffect(() => {
    onChangeHeight(visible ? heightRef.current : 0);
  }, [visible, onChangeHeight]);

  useEffect(() => {
    if (!visible || !markSessionImpression()) return;

    trackEvent(OpikEvent.MCP_BANNER_SHOWN, eventProperties);
    // The impression describes one render of one banner; re-firing it because
    // a property object was recreated would be the bug, not the fix.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible]);

  const handleCtaClick = useCallback(() => {
    trackEvent(OpikEvent.MCP_BANNER_CTA_CLICKED, eventProperties);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaceName, copyVariant]);

  const handleDismiss = useCallback(() => {
    trackEvent(OpikEvent.MCP_BANNER_DISMISSED, eventProperties);
    dismiss();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dismiss, workspaceName, copyVariant]);

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
          className="shrink-0 text-white underline underline-offset-2 hover:text-white focus-visible:ring-white"
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
      <Button
        variant="ghostInverted"
        size="icon-2xs"
        aria-label="Dismiss announcement"
        onClick={handleDismiss}
        className="shrink-0 focus-visible:ring-white"
      >
        <X />
      </Button>
    </div>
  );
};

export default McpAnnouncementBanner;
