import React from "react";

import Banner from "@/v2/layout/Banner/Banner";
import { UseNoticeBannerResult } from "./useNoticeBanner";

interface NoticeBannerProps extends UseNoticeBannerResult {
  onChangeHeight: (height: number) => void;
}

const NoticeBanner: React.FC<NoticeBannerProps> = ({
  notice,
  dismiss,
  onChangeHeight,
}) => {
  const icon = notice?.iconUrl ? (
    <img src={notice.iconUrl} alt="" className="size-4 shrink-0" />
  ) : (
    notice?.icon && <span className="shrink-0">{notice.icon}</span>
  );

  return (
    <Banner
      visible={Boolean(notice)}
      onChangeHeight={onChangeHeight}
      ariaLabel="Product notice"
      icon={icon}
      onDismiss={notice?.dismissible ? dismiss : undefined}
      dismissLabel="Dismiss notice"
      className="bg-warning-box-bg text-warning-box-text"
      style={{
        backgroundColor: notice?.backgroundColor,
        color: notice?.textColor,
      }}
    >
      <span>
        {notice?.message}
        {notice?.ctaUrl && (
          <>
            {" "}
            <a
              href={notice.ctaUrl}
              target="_blank"
              rel="noreferrer"
              className="underline underline-offset-2"
            >
              {notice.ctaText || notice.ctaUrl}
            </a>
          </>
        )}
      </span>
    </Banner>
  );
};

export default NoticeBanner;
