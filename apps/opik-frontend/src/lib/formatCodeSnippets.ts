import { maskAPIKey } from "./utils";
import { BASE_API_URL } from "@/api/api";

export const OPIK_API_KEY_TEMPLATE = "# INJECT_OPIK_CONFIGURATION";

export const OPIK_URL_OVERRIDE_CONFIG = `os.environ["OPIK_URL_OVERRIDE"] = "${new URL(
  BASE_API_URL,
  window.location.origin,
).toString()}"`;
export const buildApiKeyConfig = (apiKey: string, masked = false) =>
  `os.environ["OPIK_API_KEY"] = "${masked ? maskAPIKey(apiKey) : apiKey}"`;
export const buildWorkspaceNameConfig = (workspaceName: string) =>
  `os.environ["OPIK_WORKSPACE"] = "${workspaceName}"`;

type PutConfigInCodeArgs = {
  code: string;
  workspaceName: string;
  apiKey?: string;
  shouldMaskApiKey?: boolean;
};

export const getConfigCode = (
  workspaceName: string,
  apiKey?: string,
  shouldMaskApiKey = false,
) => {
  if (!apiKey) return OPIK_URL_OVERRIDE_CONFIG;

  const apiKeyConfig = buildApiKeyConfig(apiKey, shouldMaskApiKey);
  const workspaceConfig = buildWorkspaceNameConfig(workspaceName);

  return `${apiKeyConfig} \n${workspaceConfig}`;
};

export const putConfigInCode = ({
  code,
  workspaceName,
  apiKey,
  shouldMaskApiKey,
}: PutConfigInCodeArgs): string => {
  if (apiKey) {
    const configCode = getConfigCode(workspaceName, apiKey, shouldMaskApiKey);

    return code.replace(OPIK_API_KEY_TEMPLATE, configCode);
  }

  return code.replace(OPIK_API_KEY_TEMPLATE, OPIK_URL_OVERRIDE_CONFIG);
};
