import { BASE_API_URL } from "@/api/api";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import { maskAPIKey } from "@/lib/utils";
import useAppStore from "@/store/AppStore";

const getConfigCode = (
  workspaceName: string,
  apiKey?: string,
  shouldMaskApiKey = false,
) => {
  if (!apiKey)
    return `os.environ["OPIK_URL_OVERRIDE"] = "${window.location.origin}${BASE_API_URL}"`;

  const apiKeyConfig = `os.environ["OPIK_API_KEY"] = "${
    shouldMaskApiKey ? maskAPIKey(apiKey) : apiKey
  }"`;
  const workspaceConfig = `os.environ["OPIK_WORKSPACE"] = "${workspaceName}"`;

  return `${apiKeyConfig} \n${workspaceConfig}`;
};

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
