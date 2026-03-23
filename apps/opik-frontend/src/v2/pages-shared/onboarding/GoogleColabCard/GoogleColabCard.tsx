import React from "react";
import GoogleColabCardCore from "@/shared/GoogleColabCardCore/GoogleColabCardCore";
import { GoogleColabCardCoreProps } from "@/types/shared";
import usePluginsStore from "@/store/PluginsStore";

const GoogleColabCard: React.FC<GoogleColabCardCoreProps> = (props) => {
  const GoogleColabCard = usePluginsStore((state) => state.GoogleColabCard);

  if (GoogleColabCard) {
    return <GoogleColabCard {...props} />;
  }

  return <GoogleColabCardCore {...props} />;
};

export default GoogleColabCard;
