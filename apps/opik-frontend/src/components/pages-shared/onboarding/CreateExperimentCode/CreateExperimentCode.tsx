import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import { putConfigInCode } from "@/lib/formatCodeSnippets";
import useAppStore, { useUserApiKey } from "@/store/AppStore";

export type CreateExperimentCodeProps = {
  code: string;
};
const CreateExperimentCode: React.FC<CreateExperimentCodeProps> = ({
  code,
}) => {
  const apiKey = useUserApiKey();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { code: codeWithConfig } = putConfigInCode({
    code,
    workspaceName,
    apiKey,
    shouldMaskApiKey: true,
  });
  const { code: codeWithConfigToCopy } = putConfigInCode({
    code,
    workspaceName,
    apiKey,
  });

  return (
    <CodeHighlighter data={codeWithConfig} copyData={codeWithConfigToCopy} />
  );
};

export default CreateExperimentCode;
