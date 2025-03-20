import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import { putConfigInCode } from "@/lib/formatCodeSnippets";
import useAppStore from "@/store/AppStore";

export type CreateExperimentCodeCoreProps = {
  apiKey?: string;
  code: string;
};
const CreateExperimentCodeCore: React.FC<CreateExperimentCodeCoreProps> = ({
  apiKey,
  code,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { code: codeWithConfig } = putConfigInCode(
    code,
    workspaceName,
    apiKey,
    true,
  );
  const { code: codeWithConfigToCopy } = putConfigInCode(
    code,
    workspaceName,
    apiKey,
  );

  return (
    <CodeHighlighter data={codeWithConfig} copyData={codeWithConfigToCopy} />
  );
};

export default CreateExperimentCodeCore;
