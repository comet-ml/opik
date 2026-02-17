import usePluginsStore from "@/store/PluginsStore";
import PageGuard from "@/components/layout/PageGuard/PageGuard";

const ExperimentsPageGuard = () => {
  const ExperimentsPageGuardPlugin = usePluginsStore(
    (state) => state.ExperimentsPageGuard,
  );

  if (ExperimentsPageGuardPlugin) {
    return <ExperimentsPageGuardPlugin />;
  }

  return <PageGuard canViewPage />;
};

export default ExperimentsPageGuard;
