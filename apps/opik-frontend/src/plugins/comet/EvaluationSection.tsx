import useUserPermission from "@/plugins/comet/useUserPermission";
import EvaluationSectionContent from "@/components/pages/HomePage/EvaluationSection/EvaluationSection";

const EvaluationSection = () => {
  const { canViewExperiments } = useUserPermission();

  return <EvaluationSectionContent canViewExperiments={!!canViewExperiments} />;
};

export default EvaluationSection;
