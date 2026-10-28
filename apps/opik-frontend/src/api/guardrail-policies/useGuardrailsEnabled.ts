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
 * would turn a hard load of the page into a "no access" flash. A failed lookup is not an answer: it comes back as
 * isError with a refetch, so callers can offer a retry instead of showing guardrails as off.
 */
export default function useGuardrailsEnabled() {
  const workspaceName = useActiveWorkspaceName();
  const getGuardrailsEnabled = usePluginsStore(
    (state) => state.getGuardrailsEnabled,
  );
  const {
    data: toggles,
    isPending: isTogglesPending,
    isError: isTogglesError,
    refetch: refetchToggles,
  } = useFeatureToggle({ workspaceName });
  const isToggleOn = Boolean(
    toggles?.[FeatureToggleKeys.GUARDRAILS_VALIDATIONS_ENABLED],
  );

  const {
    data: isOrganizationEnabled,
    isPending: isOrganizationPending,
    isError: isOrganizationError,
    refetch: refetchOrganization,
  } = useQuery({
    queryKey: [GUARDRAILS_ENABLED_KEY, { workspaceName }],
    queryFn: ({ signal }) => getGuardrailsEnabled!(workspaceName, signal),
    enabled: Boolean(isToggleOn && getGuardrailsEnabled && workspaceName),
    staleTime: 60000,
  });

  if (isTogglesError) {
    return {
      isEnabled: false,
      isPending: false,
      isError: true,
      refetch: refetchToggles,
    };
  }
  if (isTogglesPending) {
    return {
      isEnabled: false,
      isPending: true,
      isError: false,
      refetch: refetchToggles,
    };
  }
  if (!isToggleOn) {
    return {
      isEnabled: false,
      isPending: false,
      isError: false,
      refetch: refetchToggles,
    };
  }
  if (!getGuardrailsEnabled) {
    return {
      isEnabled: true,
      isPending: false,
      isError: false,
      refetch: refetchToggles,
    };
  }
  return {
    isEnabled: Boolean(isOrganizationEnabled),
    isPending: isOrganizationPending,
    isError: isOrganizationError,
    refetch: refetchOrganization,
  };
}
