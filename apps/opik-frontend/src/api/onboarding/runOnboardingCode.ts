import { CODE_EXECUTOR_SERVICE_URL } from "../api";

type RunCodeRequestArgs = {
  executionUrl: string;
  workspaceName: string;
  apiKey: string;
};
export const runOnboardingCodeRequest = async ({
  executionUrl,
  apiKey,
  workspaceName,
}: RunCodeRequestArgs) => {
  const response = await fetch(`${CODE_EXECUTOR_SERVICE_URL}/${executionUrl}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      opik_api_key: apiKey,
      comet_workspace: workspaceName,
    }),
  });

  if (!response.ok) {
    throw new Error(response.statusText);
  }

  const reader = response.body?.getReader();
  if (!reader) {
    throw new Error("No reader available");
  }

  return reader;
};
