import PageLayout from "@/components/layout/PageLayout/PageLayout";
import Loader from "@/components/shared/Loader/Loader";
import usePluginStore from "@/store/PluginsStore";
import { FeatureTogglesProvider } from "@/components/feature-toggles-provider";
import { ServerSyncProvider } from "@/components/server-sync-provider";
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
        <PermissionsProviderPlugin>{layout}</PermissionsProviderPlugin>
      ) : (
        <PermissionsProvider permissions={DEFAULT_PERMISSIONS}>
          {layout}
        </PermissionsProvider>
      )}
    </WorkspacePreloader>
  );
};

export default WorkspaceGuard;
