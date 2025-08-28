import React from "react";
import { cn } from "@/lib/utils";
import { useTheme } from "@/components/theme/ThemeProvider";
import imageLogoUrl from "/images/opik-logo.png";
import imageDarkLogoUrl from "/images/opik-logo-dark-alt.png";

type LogoProps = {
  expanded: boolean;
};

const Logo: React.FunctionComponent<LogoProps> = ({ expanded }) => {
  const { themeMode } = useTheme();

  // Use dark logo for dark theme, light logo for light theme
  const logoUrl = themeMode === "dark" ? imageDarkLogoUrl : imageLogoUrl;
  return (
    <img
      className={cn("h-8 object-cover object-left -ml-[3px] mr-[3px]", {
        "w-[32px]": !expanded,
      })}
      src={logoUrl}
      alt="opik logo"
    />
  );
};

export default Logo;
