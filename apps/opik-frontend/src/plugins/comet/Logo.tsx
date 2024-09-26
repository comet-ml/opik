import React from "react";
import { cn } from "@/lib/utils";
import imageLogoUrl from "/images/comet-logo.png";

type LogoProps = {
  expanded: boolean;
};

const Logo: React.FunctionComponent<LogoProps> = ({ expanded }) => {
  return (
    <img
      className={cn("h-8 object-cover object-left", {
        "w-[26px]": !expanded,
      })}
      src={imageLogoUrl}
      alt="comet logo"
    />
  );
};

export default Logo;
