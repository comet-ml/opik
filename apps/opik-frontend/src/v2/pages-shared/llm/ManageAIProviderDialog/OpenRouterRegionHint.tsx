import React from "react";

import useAppStore from "@/store/AppStore";
import useOpenRouterRegion from "@/api/provider-keys/useOpenRouterRegion";
import { getOpenRouterRegionName } from "@/lib/openRouterRegion";

const OpenRouterRegionHint: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data } = useOpenRouterRegion({ workspaceName });
  const regionName = getOpenRouterRegionName(data?.region);

  if (!regionName) return null;

  return (
    <span
      className="comet-body-s mt-1 text-light-slate"
      data-testid="openrouter-region-hint"
    >
      {`Region: ${regionName}`}
    </span>
  );
};

export default OpenRouterRegionHint;
