import React, { useEffect, useRef, useState } from "react";

import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { EditorState, Extension } from "@codemirror/state";
import { jsonLanguage } from "@codemirror/lang-json";
import { yamlLanguage } from "@codemirror/lang-yaml";
import { pythonLanguage } from "@codemirror/lang-python";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { useCodemirrorLineHighlight } from "@/hooks/useCodemirrorLineHighlight";
import CopyButton from "@/components/shared/CopyButton/CopyButton";
import { Button } from "@/components/ui/button";
import useRunCodeSnippet from "./useRunCodeSnippet";
import { ChevronDown, ChevronUp } from "lucide-react";
import { Link } from "@tanstack/react-router";
import { FINAL_LOG_TEMPLATE } from "../FrameworkIntegrations/integration-logs";

export enum SUPPORTED_LANGUAGE {
  json = "json",
  yaml = "yaml",
  python = "python",
}

const PLUGINS_MAP: Record<SUPPORTED_LANGUAGE, Extension> = {
  [SUPPORTED_LANGUAGE.json]: jsonLanguage,
  [SUPPORTED_LANGUAGE.yaml]: yamlLanguage,
  [SUPPORTED_LANGUAGE.python]: pythonLanguage,
};

type CodeExecutorProps = {
  data: string;
  copyData?: string;
  language?: SUPPORTED_LANGUAGE;
  executionUrl: string;
  apiKey: string;
  workspaceName: string;
  executionLogs: string[];
  highlightedLines?: number[];
  onRunCodeCallback?: () => void;
};

const CodeExecutor: React.FC<CodeExecutorProps> = ({
  data,
  copyData,
  language = SUPPORTED_LANGUAGE.python,
  executionUrl,
  apiKey,
  workspaceName,
  executionLogs,
  highlightedLines,
  onRunCodeCallback,
}) => {
  const theme = useCodemirrorTheme();
  const LineHighlightExtension = useCodemirrorLineHighlight({
    lines: highlightedLines,
  });

  const { consoleOutput, isRunning, executeCode } = useRunCodeSnippet({
    executionUrl,
    executionLogs,
    apiKey,
    workspaceName,
  });
  const [consoleIsOpened, setConsoleIsOpened] = useState(false);
  const consoleRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!consoleRef.current) return;

    consoleRef.current.scrollTo({
      top: consoleRef.current.scrollHeight,
      behavior: "smooth",
    });
  }, [consoleOutput]);

  const onRunCode = () => {
    setConsoleIsOpened(true);
    executeCode();
  };

  const toggleConsoleIsOpened = () => setConsoleIsOpened((prev) => !prev);

  return (
    <div
      className={`relative overflow-hidden rounded-md border  bg-primary-foreground ${
        consoleIsOpened ? "border-border" : "border-transparent"
      }`}
    >
      <div className="relative flex items-center justify-between border-b border-b-border py-2 pl-4 pr-2">
        <div className="flex items-center">
          <Button
            onClick={toggleConsoleIsOpened}
            size="sm"
            className="h-7 gap-1 pl-0"
            variant="ghost"
          >
            {consoleIsOpened ? (
              <ChevronUp className="size-4" />
            ) : (
              <ChevronDown className="size-4" />
            )}{" "}
            Console
          </Button>
        </div>
        <div className="flex items-center gap-2">
          {isRunning ? (
            <Button size="sm" className="h-7 gap-2 px-4" disabled>
              Running...
            </Button>
          ) : (
            <Button onClick={onRunCode} size="sm" className="h-7 gap-2 px-4">
              Run
            </Button>
          )}

          <CopyButton
            message="Successfully copied code"
            text={copyData || data}
            tooltipText="Copy code"
            className="h-7"
          />
        </div>
      </div>
      {consoleIsOpened && (
        <div
          className="h-[240px] w-full overflow-auto border border-transparent border-b-border bg-background"
          ref={consoleRef}
        >
          <div className="comet-body-s gap-4 text-balance px-4 py-3 font-code">
            <div className="text-foreground-secondary">
              Welcome to Opik! Click <span className="text-green-700">Run</span>{" "}
              to execute the code sample
            </div>
            {consoleOutput.map((log) => {
              if (log === FINAL_LOG_TEMPLATE) {
                return (
                  <div
                    key={FINAL_LOG_TEMPLATE}
                    className="gap-2 py-4 text-foreground-secondary"
                  >
                    OPIK: Your LLM calls have been logged to your Opik
                    dashboard,
                    <Button
                      size="sm"
                      variant="link"
                      className="inline-flex h-auto"
                      asChild
                    >
                      <Link
                        to="/$workspaceName/redirect/projects"
                        params={{ workspaceName }}
                        search={{
                          name: "Default Project",
                        }}
                        onClick={onRunCodeCallback}
                      >
                        view them here 🚀
                      </Link>
                    </Button>
                  </div>
                );
              }
              return (
                <div key={log} className="flex gap-2 text-foreground-secondary">
                  {log.includes("%cmd%") && (
                    <div className="flex gap-1">
                      <span className="text-[var(--codemirror-syntax-blue)]">
                        ~/sandbox
                      </span>
                    </div>
                  )}

                  <div>{log.replace("%cmd%", "")}</div>
                </div>
              );
            })}
            {isRunning && (
              <div className="h-3.5 w-1 bg-foreground-secondary"></div>
            )}
          </div>
        </div>
      )}
      <CodeMirror
        theme={theme}
        value={data}
        extensions={[
          PLUGINS_MAP[language],
          EditorView.lineWrapping,
          EditorState.readOnly.of(true),
          EditorView.editable.of(false),
          LineHighlightExtension,
        ]}
      />
    </div>
  );
};

export default CodeExecutor;
