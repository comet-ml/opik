import usePluginsStore from "@/store/PluginsStore";
import EvaluationSectionContent from "./EvaluationSection";

const EvaluationSection = () => {
  const EvaluationSectionComponent = usePluginsStore(
    (state) => state.EvaluationSection,
  );

  if (EvaluationSectionComponent) {
    return <EvaluationSectionComponent />;
  }

  return <EvaluationSectionContent canViewExperiments />;
};

export default EvaluationSection;
