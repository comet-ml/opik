import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import { OPIK_URL_OVERRIDE_CONFIG } from "@/constants/shared";
import { buildApiKeyConfig, buildWorkspaceNameConfig } from "@/lib/utils";
import useAppStore from "@/store/AppStore";

const getConfigCode = (
  workspaceName: string,
  apiKey?: string,
  shouldMaskApiKey = false,
) => {
  if (!apiKey) return OPIK_URL_OVERRIDE_CONFIG;

  const apiKeyConfig = buildApiKeyConfig(apiKey, shouldMaskApiKey);
  const workspaceConfig = buildWorkspaceNameConfig(workspaceName);

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
