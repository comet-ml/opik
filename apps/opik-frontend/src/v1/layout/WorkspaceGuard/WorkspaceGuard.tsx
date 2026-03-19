import PageLayout from "@/v1/layout/PageLayout/PageLayout";
import Loader from "@/shared/Loader/Loader";
import usePluginStore from "@/store/PluginsStore";
import { FeatureTogglesProvider } from "@/v1/feature-toggles-provider";
import { ServerSyncProvider } from "@/v1/server-sync-provider";
import PermissionsGuard from "@/v1/layout/PermissionsGuard/PermissionsGuard";
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
      {PermissionsProviderPlugin ? (
        <PermissionsProviderPlugin>
          <PermissionsGuard>{layout}</PermissionsGuard>
        </PermissionsProviderPlugin>
      ) : (
        <PermissionsProvider value={DEFAULT_PERMISSIONS}>
          {layout}
        </PermissionsProvider>
      )}
    </WorkspacePreloader>
  );
};

export default WorkspaceGuard;
