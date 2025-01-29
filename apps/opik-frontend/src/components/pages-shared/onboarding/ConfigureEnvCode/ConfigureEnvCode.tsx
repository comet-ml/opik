import React from "react";
import usePluginsStore from "@/store/PluginsStore";
import ConfigureEnvCodeCore from "./ConfigureEnvCodeCore";

const ConfigureEnvCode = () => {
  const ConfigureEnvCode = usePluginsStore((state) => state.ConfigureEnvCode);

  if (ConfigureEnvCode) {
    return <ConfigureEnvCode />;
  }

  return <ConfigureEnvCodeCore />;
};

export default ConfigureEnvCode;
