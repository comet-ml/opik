import React from "react";
import useUser from "./useUser";
import GoogleColabCardCore, {
  GoogleColabCardCoreProps,
} from "@/v1/pages-shared/onboarding/GoogleColabCard/GoogleColabCardCore";

const GoogleColabCard: React.FC<GoogleColabCardCoreProps> = (props) => {
  const { data: user } = useUser();

  if (user?.sagemakerRestrictions) return;

  return <GoogleColabCardCore {...props} />;
};

export default GoogleColabCard;
