import React from "react";
import useUser from "./useUser";
import ApiKeyCardCore from "@/components/pages-shared/onboarding/ApiKeyCard/ApiKeyCardCore";

const ApiKeyCard: React.FC = () => {
  const { data: user } = useUser();

  if (!user) return;

  return <ApiKeyCardCore apiKey={user.apiKeys[0]} />;
};

export default ApiKeyCard;
