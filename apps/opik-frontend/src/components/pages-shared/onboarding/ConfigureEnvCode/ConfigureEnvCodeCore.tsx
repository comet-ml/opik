import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import { getConfigCode } from "@/lib/formatCodeSnippets";
import useAppStore from "@/store/AppStore";

export type ConfigureEnvCodeCoreProps = {
  apiKey?: string;
};
const ConfigureEnvCodeCore = ({ apiKey }: ConfigureEnvCodeCoreProps) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const data = getConfigCode({
    workspaceName,
    apiKey,
    shouldMaskApiKey: true,
    shouldImportOS: true,
  });
  const copyData = getConfigCode({
    workspaceName,
    apiKey,
    shouldImportOS: true,
  });

  return <CodeHighlighter data={data} copyData={copyData} />;
};

export default ConfigureEnvCodeCore;
