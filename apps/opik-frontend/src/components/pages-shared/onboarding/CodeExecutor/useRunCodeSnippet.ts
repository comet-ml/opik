import { REAL_LOGS_PLACEHOLDER } from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/fake-logs";
import { CODE_EXECUTOR_SERVICE_URL } from "@/components/pages-shared/onboarding/FrameworkIntegrations/quickstart-integrations";
import { useState } from "react";

type RunCodeRequestArgs = {
  executionUrl: string;
  workspaceName: string;
  apiKey: string;
};
const runCodeRequest = async ({
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

type UseRunCodeSnippetArgs = {
  executionUrl: string;
  executionLogs: string[];
  workspaceName: string;
  apiKey: string;
};

const useRunCodeSnippet = ({
  executionUrl,
  executionLogs,
  workspaceName,
  apiKey,
}: UseRunCodeSnippetArgs) => {
  const [consoleOutput, setConsoleOutput] = useState<string[]>([]);
  const [isRunning, setIsRunning] = useState(false);

  const readStream = async (reader: ReadableStreamDefaultReader) => {
    async function read() {
      const { done } = await reader.read();

      if (done) {
        return;
      }
      return await read();
    }
    return read();
  };

  const executeCode = async () => {
    setIsRunning(true);
    setConsoleOutput([]);

    for (const log of executionLogs) {
      if (log === REAL_LOGS_PLACEHOLDER) {
        try {
          const logsStream = await runCodeRequest({
            executionUrl,
            apiKey,
            workspaceName,
          });

          await readStream(logsStream);

          continue;
        } catch (error) {
          const errorText =
            error instanceof Error ? error.message : String(error);

          setConsoleOutput((prev) => [...prev, errorText]);
          break;
        }
      }
      await new Promise((resolve) => setTimeout(resolve, Math.random() * 1000));
      setConsoleOutput((prev) => [...prev, log]);
    }

    setIsRunning(false);
  };

  return {
    executeCode,
    consoleOutput,
    isRunning,
  };
};

export default useRunCodeSnippet;
