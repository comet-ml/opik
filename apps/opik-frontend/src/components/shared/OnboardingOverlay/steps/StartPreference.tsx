import usePluginsStore from "@/store/PluginsStore";
import StartPreferenceContent from "./StartPreferenceContent";

const StartPreference = () => {
  const StartPreference = usePluginsStore((state) => state.StartPreference);

  if (StartPreference) {
    return <StartPreference />;
  }

  return <StartPreferenceContent canViewExperiments />;
};

export default StartPreference;
