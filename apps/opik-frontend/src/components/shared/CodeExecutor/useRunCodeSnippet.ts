import { REAL_LOGS_PLACEHOLDER } from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/fake-logs";
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
  try {
    const response = await fetch(
      `${import.meta.env.VITE_GET_STARTED_API_URL}/${executionUrl}`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          opik_api_key: apiKey,
          comet_workspace: workspaceName,
        }),
      },
    );

    if (!response.ok) {
      return `Error: ${response.statusText}`;
    }

    const reader = response.body?.getReader();

    if (!reader) {
      return `Error: ${response.statusText}`;
    }

    return reader;
  } catch (error) {
    return `Run code snippet execution is faled`;
  }
};

type UseRunCodeSnippetArgs = {
  executionUrl: string;
  executionFakeLogs: string[];
  workspaceName: string;
  apiKey: string;
};

const useRunCodeSnippet = ({
  executionUrl,
  executionFakeLogs,
  workspaceName,
  apiKey,
}: UseRunCodeSnippetArgs) => {
  const [consoleOutput, setConsoleOutput] = useState<string[]>([]);
  const [isRunning, setIsRunning] = useState(false);

  const readStream = async (reader: ReadableStreamDefaultReader) => {
    async function read() {
      const { done, value } = await reader.read();

      if (done) {
        return;
      }

      const text = new TextDecoder().decode(value);

      if (!text.includes("END STREAM")) {
        // TODO uncomment once code snippents are editable
        // setConsoleOutput((prev) => [...prev, text]);
      }
      return await read();
    }
    return await read();
  };

  const executeCode = async () => {
    setIsRunning(true);
    setConsoleOutput([]);

    for (const log of executionFakeLogs) {
      if (log === REAL_LOGS_PLACEHOLDER) {
        const logsStream = await runCodeRequest({
          executionUrl,
          apiKey,
          workspaceName,
        });

        if (typeof logsStream == "string") {
          setConsoleOutput((prev) => [...prev, logsStream]);
          break;
        }

        await readStream(logsStream);

        continue;
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
