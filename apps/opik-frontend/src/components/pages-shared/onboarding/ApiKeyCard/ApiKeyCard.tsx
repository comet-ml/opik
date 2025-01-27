import React from "react";
import usePluginsStore from "@/store/PluginsStore";

const ApiKeyCard = () => {
  const ApiKeyCard = usePluginsStore((state) => state.ApiKeyCard);

  if (ApiKeyCard) {
    return <ApiKeyCard />;
  }

  return null;
};

export default ApiKeyCard;
