import React from "react";
import useUser from "./useUser";
import GoogleColabCardCore from "@/shared/GoogleColabCardCore/GoogleColabCardCore";
import { GoogleColabCardCoreProps } from "@/types/shared";

const GoogleColabCard: React.FC<GoogleColabCardCoreProps> = (props) => {
  const { data: user } = useUser();

  if (user?.sagemakerRestrictions) return;

  return <GoogleColabCardCore {...props} />;
};

export default GoogleColabCard;
