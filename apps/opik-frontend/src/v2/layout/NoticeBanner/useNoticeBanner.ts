import { useCallback } from "react";
import useLocalStorageState from "use-local-storage-state";
import { useFeatureFlagEnabled, useFeatureFlagPayload } from "posthog-js/react";

export const NOTICE_BANNER_FEATURE_FLAG_KEY = "product-notice-banner";

// Stores the dismissed message, so a new message shows again.
const NOTICE_BANNER_DISMISSED_KEY = "product-notice-banner-dismissed";

const SAFE_URL_PROTOCOLS = ["https:", "http:", "mailto:"];

export type NoticeBannerContent = {
  message: string;
  ctaText?: string;
  ctaUrl?: string;
  backgroundColor?: string;
  textColor?: string;
  icon?: string;
  iconUrl?: string;
  dismissible: boolean;
};

export type UseNoticeBannerResult = {
  notice: NoticeBannerContent | null;
  dismiss: () => void;
};

const asString = (value: unknown) =>
  typeof value === "string" && value ? value : undefined;

const asSafeUrl = (value: unknown) => {
  const url = asString(value);
  if (!url) return undefined;
  try {
    return SAFE_URL_PROTOCOLS.includes(
      new URL(url, window.location.origin).protocol,
    )
      ? url
      : undefined;
  } catch {
    return undefined;
  }
};

const parsePayload = (payload: unknown): NoticeBannerContent | null => {
  if (!payload || typeof payload !== "object") return null;
  const raw = payload as Record<string, unknown>;
  const message = asString(raw.message);
  if (!message) return null;

  return {
    message,
    ctaText: asString(raw.cta_text),
    ctaUrl: asSafeUrl(raw.cta_url),
    backgroundColor: asString(raw.background_color),
    textColor: asString(raw.text_color),
    icon: asString(raw.icon),
    iconUrl: asSafeUrl(raw.icon_url),
    dismissible: raw.dismiss !== false,
  };
};

export const useNoticeBanner = (): UseNoticeBannerResult => {
  const enabled = useFeatureFlagEnabled(NOTICE_BANNER_FEATURE_FLAG_KEY);
  const payload = useFeatureFlagPayload(NOTICE_BANNER_FEATURE_FLAG_KEY);
  const [dismissedMessage, setDismissedMessage] = useLocalStorageState<
    string | null
  >(NOTICE_BANNER_DISMISSED_KEY, { defaultValue: null });

  const content = enabled ? parsePayload(payload) : null;
  const isDismissed =
    !!content?.dismissible && dismissedMessage === content.message;
  const notice = content && !isDismissed ? content : null;
  const message = notice?.message ?? null;

  const dismiss = useCallback(() => {
    if (message) setDismissedMessage(message);
  }, [message, setDismissedMessage]);

  return { notice, dismiss };
};
