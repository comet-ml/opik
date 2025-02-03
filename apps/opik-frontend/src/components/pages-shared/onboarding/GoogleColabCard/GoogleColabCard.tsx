import React from "react";
import GoogleColabCardCore, {
  GoogleColabCardCoreProps,
} from "./GoogleColabCardCore";
import usePluginsStore from "@/store/PluginsStore";

const GoogleColabCard: React.FC<GoogleColabCardCoreProps> = (props) => {
  const GoogleColabCard = usePluginsStore((state) => state.GoogleColabCard);

  if (GoogleColabCard) {
    return <GoogleColabCard {...props} />;
  }

  return <GoogleColabCardCore {...props} />;
};

export default GoogleColabCard;
