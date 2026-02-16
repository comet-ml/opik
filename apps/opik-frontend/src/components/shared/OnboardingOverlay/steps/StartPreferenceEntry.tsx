import usePluginsStore from "@/store/PluginsStore";
import StartPreferenceContent from "./StartPreference";

const StartPreferenceEntry = () => {
  const StartPreference = usePluginsStore((state) => state.StartPreference);

  if (StartPreference) {
    return <StartPreference />;
  }

  return <StartPreferenceContent canViewExperiments />;
};

export default StartPreferenceEntry;
