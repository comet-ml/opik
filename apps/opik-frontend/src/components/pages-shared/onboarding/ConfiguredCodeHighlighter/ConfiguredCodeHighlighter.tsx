import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import { putConfigInCode } from "@/lib/formatCodeSnippets";
import useAppStore, { useUserApiKey } from "@/store/AppStore";

export type ConfiguredCodeHighlighterProps = {
  code: string;
  projectName?: string;
};
const ConfiguredCodeHighlighter: React.FC<ConfiguredCodeHighlighterProps> = ({
  code,
  projectName,
}) => {
  const apiKey = useUserApiKey();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { code: codeWithConfig } = putConfigInCode({
    code,
    workspaceName,
    apiKey,
    shouldMaskApiKey: true,
    projectName,
  });
  const { code: codeWithConfigToCopy } = putConfigInCode({
    code,
    workspaceName,
    apiKey,
    projectName,
  });

  return (
    <CodeHighlighter data={codeWithConfig} copyData={codeWithConfigToCopy} />
  );
};

export default ConfiguredCodeHighlighter;
