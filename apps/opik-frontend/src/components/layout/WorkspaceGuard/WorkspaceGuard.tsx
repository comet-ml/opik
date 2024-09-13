import PageLayout from "@/components/layout/PageLayout/PageLayout";
import Loader from "@/components/shared/Loader/Loader";
import usePluginStore from "@/store/PluginsStore";

const WorkspaceGuard = ({ Layout = PageLayout }) => {
  const WorkspacePreloader = usePluginStore(
    (state) => state.WorkspacePreloader,
  );

  if (!WorkspacePreloader) {
    return <Loader />;
  }

  return (
    <WorkspacePreloader>
      <Layout />
    </WorkspacePreloader>
  );
};

export default WorkspaceGuard;
