import usePluginsStore from "@/store/PluginsStore";
import ExperimentsGuardCore from "./ExperimentsGuard";

const ExperimentsGuardEntry = () => {
  const ExperimentsGuard = usePluginsStore((state) => state.ExperimentsGuard);

  if (ExperimentsGuard) {
    return <ExperimentsGuard />;
  }

  return <ExperimentsGuardCore canViewExperiments />;
};

export default ExperimentsGuardEntry;
