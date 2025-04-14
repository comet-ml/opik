import { maskAPIKey } from "./utils";
import { BASE_API_URL } from "@/api/api";

export const OPIK_API_KEY_TEMPLATE = "# INJECT_OPIK_CONFIGURATION";
export const OPIK_HIGHLIGHT_LINE_TEMPLATE = " # HIGHLIGHTED_LINE";

export const buildApiKeyConfig = (
  apiKey: string,
  masked = false,
  withHighlight = false,
) =>
  `os.environ["OPIK_API_KEY"] = "${masked ? maskAPIKey(apiKey) : apiKey}"${
    withHighlight ? OPIK_HIGHLIGHT_LINE_TEMPLATE : ""
  }`;

export const buildProjectNameConfig = (projectName: string) =>
  `os.environ["OPIK_PROJECT_NAME"] = "${projectName}"`;

export const buildWorkspaceNameConfig = (
  workspaceName: string,
  withHighlight = false,
) =>
  `os.environ["OPIK_WORKSPACE"] = "${workspaceName}"${
    withHighlight ? OPIK_HIGHLIGHT_LINE_TEMPLATE : ""
  }`;

export const buildOpikUrlOverrideConfig = (withHighlight = false) =>
  `os.environ["OPIK_URL_OVERRIDE"] = "${new URL(
    BASE_API_URL,
    window.location.origin,
  ).toString()}${withHighlight ? OPIK_HIGHLIGHT_LINE_TEMPLATE : ""}"`;

type PutConfigInCodeArgs = {
  code: string;
  workspaceName: string;
  apiKey?: string;
  shouldMaskApiKey?: boolean;
  withHighlight?: boolean;
  projectName?: string;
};

export const getConfigCode = (
  workspaceName: string,
  apiKey?: string,
  shouldMaskApiKey = false,
  withHighlight = false,
  projectName?: string,
) => {
  if (!apiKey) return buildOpikUrlOverrideConfig(withHighlight);

  const apiKeyConfig = buildApiKeyConfig(
    apiKey,
    shouldMaskApiKey,
    withHighlight,
  );
  const workspaceConfig = buildWorkspaceNameConfig(
    workspaceName,
    withHighlight,
  );
  const projectNameConfig = projectName
    ? `\n${buildProjectNameConfig(projectName)}`
    : "";

  return `${apiKeyConfig} \n${workspaceConfig}${projectNameConfig}`;
};

export const putConfigInCode = ({
  code,
  workspaceName,
  apiKey,
  shouldMaskApiKey,
  withHighlight = false,
  projectName,
}: PutConfigInCodeArgs): { code: string; lines: number[] } => {
  let patchedCode = "";

  if (apiKey) {
    const configCode = getConfigCode(
      workspaceName,
      apiKey,
      shouldMaskApiKey,
      withHighlight,
      projectName,
    );

    patchedCode = code.replace(OPIK_API_KEY_TEMPLATE, configCode);
  } else {
    patchedCode = code.replace(
      OPIK_API_KEY_TEMPLATE,
      buildOpikUrlOverrideConfig(withHighlight),
    );
  }

  return {
    code: patchedCode.replaceAll(OPIK_HIGHLIGHT_LINE_TEMPLATE, ""),
    lines: patchedCode.split("\n").reduce<number[]>((acc, line, idx) => {
      if (line.includes(OPIK_HIGHLIGHT_LINE_TEMPLATE)) {
        acc.push(idx + 1);
      }

      return acc;
    }, []),
  };
};
