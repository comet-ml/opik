import React from "react";
import { cn } from "@/lib/utils";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import imageLogoUrl from "/images/opik-logo.png";
import imageLogoInvertedUrl from "/images/opik-logo-inverted.png";

type LogoProps = {
  expanded: boolean;
};

const Logo: React.FunctionComponent<LogoProps> = ({ expanded }) => {
  const { themeMode } = useTheme();

  return (
    <img
      className={cn(
        "object-cover object-left",
        expanded ? "h-[18px]" : "h-[18px] w-[18px]",
      )}
      src={themeMode === THEME_MODE.DARK ? imageLogoInvertedUrl : imageLogoUrl}
      alt="opik logo"
    />
  );
};

export default Logo;
