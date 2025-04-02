import PageLayout from "@/components/layout/PageLayout/PageLayout";
import Loader from "@/components/shared/Loader/Loader";
import usePluginStore from "@/store/PluginsStore";
import { FeatureTogglesProvider } from "@/components/feature-toggles-provider";

const WorkspaceGuard = ({
  Layout = PageLayout,
}: {
  Layout: React.FC<{ children?: React.ReactNode }>;
}) => {
  const WorkspacePreloader = usePluginStore(
    (state) => state.WorkspacePreloader,
  );

  if (!WorkspacePreloader) {
    return <Loader />;
  }

  return (
    <WorkspacePreloader>
      <FeatureTogglesProvider>
        <Layout />
      </FeatureTogglesProvider>
    </WorkspacePreloader>
  );
};

export default WorkspaceGuard;
