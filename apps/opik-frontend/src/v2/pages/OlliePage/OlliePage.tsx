import noop from "lodash/noop";
import { Navigate, useParams } from "@tanstack/react-router";
import usePluginsStore from "@/store/PluginsStore";
import {
  useAreFeatureTogglesLoaded,
  useIsFeatureEnabled,
} from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import Loader from "@/shared/Loader/Loader";

const OlliePage = () => {
  const { workspaceName, projectId } = useParams({
    strict: false,
  }) as { workspaceName: string; projectId: string };

  const AssistantSidebar = usePluginsStore((state) => state.AssistantSidebar);
  const ollieEnabled = useIsFeatureEnabled(FeatureToggleKeys.OLLIE_ENABLED);
  const featureTogglesLoaded = useAreFeatureTogglesLoaded();

  if (!featureTogglesLoaded) {
    return <Loader />;
  }

  if (!AssistantSidebar || !ollieEnabled) {
    return (
      <Navigate
        to="/$workspaceName/projects/$projectId/home"
        params={{ workspaceName, projectId }}
        replace
      />
    );
  }

  return (
    <div className="-mx-6 flex h-full">
      <AssistantSidebar surface="page" onWidthChange={noop} />
    </div>
  );
};

export default OlliePage;
