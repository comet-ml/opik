import usePluginsStore from "@/store/PluginsStore";
import EvaluationSectionContent from "./EvaluationSection";

const EvaluationSectionEntry = () => {
  const EvaluationSection = usePluginsStore((state) => state.EvaluationSection);

  if (EvaluationSection) {
    return <EvaluationSection />;
  }

  return <EvaluationSectionContent canViewExperiments />;
};

export default EvaluationSectionEntry;
