import usePluginsStore from "@/store/PluginsStore";
import NoAccessPageGuard from "@/components/layout/NoAccessPageGuard/NoAccessPageGuard";

const ExperimentsPageGuard = () => {
  const ExperimentsPageGuardPlugin = usePluginsStore(
    (state) => state.ExperimentsPageGuard,
  );

  if (ExperimentsPageGuardPlugin) {
    return <ExperimentsPageGuardPlugin />;
  }

  return <NoAccessPageGuard canViewPage />;
};

export default ExperimentsPageGuard;
