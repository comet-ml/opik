import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import { getConfigCode } from "@/lib/formatCodeSnippets";
import useAppStore from "@/store/AppStore";

export type ConfigureEnvCodeCoreProps = {
  apiKey?: string;
};
const ConfigureEnvCodeCore = ({ apiKey }: ConfigureEnvCodeCoreProps) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <CodeHighlighter
      data={getConfigCode(workspaceName, apiKey, true)}
      copyData={getConfigCode(workspaceName, apiKey)}
    />
  );
};

export default ConfigureEnvCodeCore;
