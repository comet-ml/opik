import React, { useLayoutEffect } from "react";
import { X } from "lucide-react";
import useLocalStorageState from "use-local-storage-state";
import { useFeatureFlagEnabled, useFeatureFlagPayload } from "posthog-js/react";

import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

export const NOTICE_BANNER_FEATURE_FLAG_KEY = "product-notice-banner";

// Stores the dismissed message, so a new message shows again.
const NOTICE_BANNER_DISMISSED_KEY = "product-notice-banner-dismissed";

type NoticeBannerPayload = {
  message?: string;
  cta_text?: string;
  cta_url?: string;
  background_color?: string;
  text_color?: string;
  icon?: string;
  icon_url?: string;
  dismiss?: boolean;
};

interface NoticeBannerProps {
  onChangeHeight: (height: number) => void;
}

const NoticeBanner: React.FC<NoticeBannerProps> = ({ onChangeHeight }) => {
  const payload = useFeatureFlagPayload(NOTICE_BANNER_FEATURE_FLAG_KEY) as
    | NoticeBannerPayload
    | undefined;
  const enabled = useFeatureFlagEnabled(NOTICE_BANNER_FEATURE_FLAG_KEY);
  const [dismissedMessage, setDismissedMessage] = useLocalStorageState<
    string | null
  >(NOTICE_BANNER_DISMISSED_KEY, { defaultValue: null });
  const dismissible = payload?.dismiss !== false;
  const isDismissed = dismissible && dismissedMessage === payload?.message;
  const message = enabled && !isDismissed ? payload?.message : undefined;

  const { ref } = useObserveResizeNode<HTMLDivElement>((node) =>
    onChangeHeight(node.clientHeight),
  );

  useLayoutEffect(() => {
    if (!message) onChangeHeight(0);
  }, [message, onChangeHeight]);

  if (!message) {
    return null;
  }

  return (
    <div
      ref={ref}
      role="region"
      aria-label="Product notice"
      className="z-10 flex min-h-8 items-center gap-1.5 bg-warning-box-bg px-2 py-1 text-warning-box-text"
      style={{
        backgroundColor: payload?.background_color,
        color: payload?.text_color,
      }}
    >
      <div className="flex min-w-0 flex-1 items-center justify-center gap-1.5">
        {payload?.icon_url ? (
          <img src={payload.icon_url} alt="" className="size-4 shrink-0" />
        ) : (
          payload?.icon && (
            <span className="comet-body-xs shrink-0">{payload.icon}</span>
          )
        )}
        <span className="comet-body-xs">
          {message}
          {payload?.cta_url && (
            <>
              {" "}
              <a
                href={payload.cta_url}
                target="_blank"
                rel="noreferrer"
                className="underline underline-offset-2"
              >
                {payload.cta_text || payload.cta_url}
              </a>
            </>
          )}
        </span>
      </div>
      {dismissible && (
        <TooltipWrapper content="Dismiss">
          <Button
            variant="ghost"
            size="icon-2xs"
            aria-label="Dismiss notice"
            onClick={() => setDismissedMessage(message)}
            className="shrink-0 text-inherit hover:bg-black/5 hover:text-inherit"
          >
            <X />
          </Button>
        </TooltipWrapper>
      )}
    </div>
  );
};

export default NoticeBanner;
