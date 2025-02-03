import React from "react";
import useUser from "./useUser";
import FrameworkIntegrationsContent from "@/components/pages-shared/onboarding/FrameworkIntegrations/FrameworkIntegrationsContent";
import { FrameworkIntegrationsProps } from "@/components/pages-shared/onboarding/FrameworkIntegrations/FrameworkIntegrations";

const FrameworkIntegrations: React.FC<FrameworkIntegrationsProps> = (props) => {
  const { data: user } = useUser();

  if (!user) return;

  return <FrameworkIntegrationsContent apiKey={user.apiKeys[0]} {...props} />;
};

export default FrameworkIntegrations;
