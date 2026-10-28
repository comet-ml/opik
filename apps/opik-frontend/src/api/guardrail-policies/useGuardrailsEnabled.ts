import { useQuery } from "@tanstack/react-query";
import { GUARDRAILS_ENABLED_KEY } from "@/api/api";
import useFeatureToggle from "@/api/feature-toggle/useFeatureToggle";
import { useActiveWorkspaceName } from "@/store/AppStore";
import usePluginsStore from "@/store/PluginsStore";
import { FeatureToggleKeys } from "@/types/feature-toggles";

/**
 * Whether guardrails are available in the active workspace: the deployment runs them (the backend toggle) and,
 * where a platform plugin can say so, the workspace's organization has them enabled. Without the plugin (OSS) the
 * toggle alone decides.
 *
 * Reads the toggles query itself rather than the provider, which reports its defaults until the toggles arrive and
 * would turn a hard load of the page into a "no access" flash.
 */
export default function useGuardrailsEnabled() {
  const workspaceName = useActiveWorkspaceName();
  const getGuardrailsEnabled = usePluginsStore(
    (state) => state.getGuardrailsEnabled,
  );
  const { data: toggles, isPending: isTogglesPending } = useFeatureToggle({
    workspaceName,
  });
  const isToggleOn = Boolean(
    toggles?.[FeatureToggleKeys.GUARDRAILS_VALIDATIONS_ENABLED],
  );

  const {
    data: isOrganizationEnabled,
    isPending: isOrganizationPending,
    isError: isOrganizationError,
    refetch,
  } = useQuery({
    queryKey: [GUARDRAILS_ENABLED_KEY, { workspaceName }],
    queryFn: ({ signal }) => getGuardrailsEnabled!(workspaceName, signal),
    enabled: Boolean(isToggleOn && getGuardrailsEnabled && workspaceName),
    staleTime: 60000,
  });

  if (isTogglesPending) {
    return { isEnabled: false, isPending: true, isError: false, refetch };
  }
  if (!isToggleOn) {
    return { isEnabled: false, isPending: false, isError: false, refetch };
  }
  if (!getGuardrailsEnabled) {
    return { isEnabled: true, isPending: false, isError: false, refetch };
  }
  // A failed lookup is not an answer: callers show it as an error, not as guardrails being off.
  return {
    isEnabled: Boolean(isOrganizationEnabled),
    isPending: isOrganizationPending,
    isError: isOrganizationError,
    refetch,
  };
}
