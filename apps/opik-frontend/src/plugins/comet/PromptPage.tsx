import useUserPermission from "@/plugins/comet/useUserPermission";
import PromptPageContent from "@/components/pages/PromptPage/PromptPage";

const PromptPage = () => {
  const { canViewExperiments } = useUserPermission();

  return <PromptPageContent canViewExperiments={canViewExperiments} />;
};

export default PromptPage;
