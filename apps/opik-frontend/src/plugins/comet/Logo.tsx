import React from "react";
import { cn } from "@/lib/utils";
import imageLogoUrl from "/images/opik-logo.png";

type LogoProps = {
  expanded: boolean;
};

const Logo: React.FunctionComponent<LogoProps> = ({ expanded }) => {
  return (
    <img
      className={cn("h-8 object-cover object-left -ml-[3px] mr-[3px]", {
        "w-[32px]": !expanded,
      })}
      src={imageLogoUrl}
      alt="opik logo"
    />
  );
};

export default Logo;
