import PageLayout from "@/v2/layout/PageLayout/PageLayout";
import Loader from "@/shared/Loader/Loader";
import usePluginStore from "@/store/PluginsStore";
import { FeatureTogglesProvider } from "@/contexts/feature-toggles-provider";
import { ServerSyncProvider } from "@/contexts/server-sync-provider";
import PermissionsGuard from "@/v2/layout/PermissionsGuard/PermissionsGuard";
import WorkspaceVersionResolver from "@/shared/WorkspaceVersionResolver/WorkspaceVersionResolver";
import { PermissionsProvider } from "@/contexts/PermissionsContext";
import { DEFAULT_PERMISSIONS } from "@/types/permissions";

const WorkspaceGuard = ({
  Layout = PageLayout,
}: {
  Layout: React.FC<{ children?: React.ReactNode }>;
}) => {
  const WorkspacePreloader = usePluginStore(
    (state) => state.WorkspacePreloader,
  );
  const PermissionsProviderPlugin = usePluginStore(
    (state) => state.PermissionsProvider,
  );
  // Kick off Ollie pod provisioning as soon as the workspace resolves, so
  // cold-start overlaps with onboarding instead of blocking sidebar mount.
  // Rendered via plugin store so OSS builds don't import plugin internals.
  const AssistantPrewarmer = usePluginStore(
    (state) => state.AssistantPrewarmer,
  );

  if (!WorkspacePreloader) {
    return <Loader />;
  }

  const layout = (
    <FeatureTogglesProvider>
      <ServerSyncProvider>
        <Layout />
      </ServerSyncProvider>
    </FeatureTogglesProvider>
  );

  return (
    <WorkspacePreloader>
      {AssistantPrewarmer ? <AssistantPrewarmer /> : null}
      <WorkspaceVersionResolver>
        {PermissionsProviderPlugin ? (
          <PermissionsProviderPlugin>
            <PermissionsGuard>{layout}</PermissionsGuard>
          </PermissionsProviderPlugin>
        ) : (
          <PermissionsProvider value={DEFAULT_PERMISSIONS}>
            {layout}
          </PermissionsProvider>
        )}
      </WorkspaceVersionResolver>
    </WorkspacePreloader>
  );
};

export default WorkspaceGuard;
