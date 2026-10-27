import React from "react";
import useLocalStorageState from "use-local-storage-state";
import { useFeatureFlagEnabled, useFeatureFlagPayload } from "posthog-js/react";

import Banner from "@/v2/layout/Banner/Banner";

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

  const icon = payload?.icon_url ? (
    <img src={payload.icon_url} alt="" className="size-4 shrink-0" />
  ) : (
    payload?.icon && <span className="shrink-0">{payload.icon}</span>
  );

  return (
    <Banner
      visible={Boolean(message)}
      onChangeHeight={onChangeHeight}
      ariaLabel="Product notice"
      icon={icon}
      onDismiss={
        dismissible && message ? () => setDismissedMessage(message) : undefined
      }
      dismissLabel="Dismiss notice"
      className="bg-warning-box-bg text-warning-box-text"
      style={{
        backgroundColor: payload?.background_color,
        color: payload?.text_color,
      }}
    >
      <span>
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
    </Banner>
  );
};

export default NoticeBanner;
