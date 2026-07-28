import React from "react";

import { Button } from "@/ui/button";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";

interface DropdownEmptyStateProps {
  lightImageUrl: string;
  darkImageUrl: string;
  title: string;
  ctaLabel?: string;
  onCreate?: () => void;
}

const DropdownEmptyState: React.FC<DropdownEmptyStateProps> = ({
  lightImageUrl,
  darkImageUrl,
  title,
  ctaLabel,
  onCreate,
}) => {
  const { themeMode } = useTheme();
  const imageUrl = themeMode === THEME_MODE.DARK ? darkImageUrl : lightImageUrl;

  return (
    <div className="flex min-h-[160px] flex-col items-center justify-center gap-1 px-4 py-2 text-center">
      <img src={imageUrl} alt="" className="size-8 shrink-0" />
      <div className="comet-body-xs-accented pb-1 text-foreground">{title}</div>
      {ctaLabel && onCreate && (
        <Button
          variant="tableLink"
          size="2xs"
          className="h-auto px-0"
          onClick={onCreate}
        >
          {ctaLabel}
        </Button>
      )}
    </div>
  );
};

export default DropdownEmptyState;
