import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import { putConfigInCode } from "@/lib/formatCodeSnippets";
import useAppStore from "@/store/AppStore";

export type ConfiguredCodeHighlighterCoreProps = {
  apiKey?: string;
  code: string;
  projectName?: string;
};
const ConfiguredCodeHighlighterCore: React.FC<
  ConfiguredCodeHighlighterCoreProps
> = ({ apiKey, code, projectName }) => {
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

export default ConfiguredCodeHighlighterCore;
