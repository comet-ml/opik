import { runOnboardingCodeRequest } from "@/api/onboarding/runOnboardingCode";
import { useState } from "react";
import { REAL_LOGS_PLACEHOLDER } from "../FrameworkIntegrations/integration-logs";

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
      return read();
    }
    return read();
  };

  const executeCode = async () => {
    setIsRunning(true);
    setConsoleOutput([]);

    for (const log of executionLogs) {
      if (log === REAL_LOGS_PLACEHOLDER) {
        try {
          const logsStream = await runOnboardingCodeRequest({
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
