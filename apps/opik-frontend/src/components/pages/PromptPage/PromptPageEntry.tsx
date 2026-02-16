import usePluginsStore from "@/store/PluginsStore";
import PromptPageContent from "./PromptPage";

const PromptPageEntry = () => {
  const PromptPage = usePluginsStore((state) => state.PromptPage);

  if (PromptPage) {
    return <PromptPage />;
  }

  return <PromptPageContent canViewExperiments />;
};

export default PromptPageEntry;
