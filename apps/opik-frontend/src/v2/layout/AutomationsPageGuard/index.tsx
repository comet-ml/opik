import useFeatureToggle from "@/api/feature-toggle/useFeatureToggle";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import Loader from "@/shared/Loader/Loader";
import NoData from "@/shared/NoData/NoData";
import { useActiveWorkspaceName } from "@/store/AppStore";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import AutomationsRouteWrapper from "@/v2/pages/AutomationsPage/AutomationsRouteWrapper";

const AutomationsPageGuard = () => {
  const workspaceName = useActiveWorkspaceName();
  const enabled = useIsFeatureEnabled(FeatureToggleKeys.AUTOMATIONS_ENABLED);
  // The same query the toggles provider runs: on a direct page load the toggles
  // are still on their way, and "off" must not flash before they land.
  const { isPending } = useFeatureToggle({ workspaceName });

  if (!enabled) {
    return isPending ? (
      <Loader />
    ) : (
      <NoData
        title="Automations are off"
        message="This deployment does not have the automations toggle enabled."
      />
    );
  }

  return <AutomationsRouteWrapper />;
};

export default AutomationsPageGuard;
