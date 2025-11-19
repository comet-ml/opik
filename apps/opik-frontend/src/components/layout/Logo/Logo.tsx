import React from "react";
import { cn } from "@/lib/utils";
import { useTheme } from "@/components/theme-provider";
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
      className={cn("h-8 object-cover object-left -ml-[3px] mr-[3px]", {
        "w-[32px]": !expanded,
      })}
      src={themeMode === THEME_MODE.DARK ? imageLogoInvertedUrl : imageLogoUrl}
      alt="opik logo"
    />
  );
};

export default Logo;
