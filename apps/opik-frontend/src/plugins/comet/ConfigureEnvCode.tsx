import React from "react";
import useUser from "./useUser";
import ConfigureEnvCodeCore from "@/components/pages-shared/onboarding/ConfigureEnvCode/ConfigureEnvCodeCore";

const ConfigureEnvCode: React.FC = () => {
  const { data: user } = useUser();

  if (!user) return;

  return <ConfigureEnvCodeCore apiKey={user.apiKeys[0]} />;
};

export default ConfigureEnvCode;
