import usePluginsStore from "@/store/PluginsStore";
import PageGuard from "@/components/layout/PageGuard/PageGuard";

const ExperimentsGuard = () => {
  const ExperimentsGuardPlugin = usePluginsStore(
    (state) => state.ExperimentsGuard,
  );

  if (ExperimentsGuardPlugin) {
    return <ExperimentsGuardPlugin />;
  }

  return <PageGuard canViewPage />;
};

export default ExperimentsGuard;
