import React from "react";
import { cn } from "@/lib/utils";
import iconSvgUrl from "/images/opik-icon-primary.svg";

type LogoProps = {
  expanded: boolean;
};

const Logo: React.FunctionComponent<LogoProps> = ({ expanded }) => {
  return (
    <img
      className={cn("h-6 shrink-0 object-contain", {
        "w-6": !expanded,
        "w-auto": expanded,
      })}
      src={iconSvgUrl}
      alt="opik logo"
    />
  );
};

export default Logo;
