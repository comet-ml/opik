import React, { useState } from "react";
import ApiKeyCard from "../ApiKeyCard/ApiKeyCard";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import evaluatePromptsCode from "./evaluation-scripts/EvaluatePrompts.py?raw";
import evaluateLLMCode from "./evaluation-scripts/EvaluateLLM.py?raw";
import { Button } from "@/components/ui/button";
import { Link } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import { SheetClose } from "@/components/ui/sheet";
import { Blocks, FileTerminal, FlaskConical, LucideIcon } from "lucide-react";

type TabValue = "evaluate-prompts" | "evaluate-llm" | "using-playground";
type TabItem = {
  label: string;
  value: TabValue;
  icon: LucideIcon;
};
const tabList: TabItem[] = [
  {
    label: "Evaluate prompts",
    value: "evaluate-prompts",
    icon: FileTerminal,
  },
  {
    label: "Evaluate LLM application",
    value: "evaluate-llm",
    icon: FlaskConical,
  },
  {
    label: "Using the playground",
    value: "using-playground",
    icon: Blocks,
  },
];

const codeSnippets: Record<
  "evaluate-prompts" | "evaluate-llm",
  {
    code: string;
    codeToCopy: string;
  }
> = {
  "evaluate-llm": {
    code: evaluateLLMCode,
    codeToCopy: evaluateLLMCode,
  },
  "evaluate-prompts": {
    code: evaluatePromptsCode,
    codeToCopy: evaluatePromptsCode,
  },
};

export type EvaluationExamplesProps = {
  apiKey?: string;
};
const EvaluationExamples: React.FC<EvaluationExamplesProps> = ({ apiKey }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [exampleTab, setExampleTab] = useState<TabItem>(tabList[0]);

  return (
    <div className="m-auto flex w-full max-w-[1250px] gap-6">
      <div className="flex w-[250px] shrink-0 flex-col gap-4 self-start">
        <h4 className="comet-title-s">Select framework</h4>
        <ul className="flex flex-col gap-2">
          {tabList.map((item) => (
            <li
              key={item.label}
              className="comet-body-s text-foreground hover:bg-primary-foreground data-[status=active]:bg-primary-100 flex h-10 w-full cursor-pointer items-center gap-2 rounded-md pl-2 pr-4"
              onClick={() => setExampleTab(item)}
              data-status={
                item.value === exampleTab.value ? "active" : "inactive"
              }
            >
              <item.icon className="size-4 shrink-0" />
              <div className="ml-1 truncate">{item.label}</div>
            </li>
          ))}
        </ul>
      </div>
      <div className="flex min-w-[650px] flex-1 gap-6">
        <div className="flex w-full flex-1 flex-col">
          {exampleTab.value === "evaluate-prompts" && (
            <div className="flex flex-col gap-6 rounded-md border bg-white p-6">
              <div>
                <div className="comet-body-s mb-3">
                  1. Install Opik using pip from the command line.
                </div>
                <div className="min-h-7">
                  <CodeHighlighter data="pip install opik" />
                </div>
              </div>
              <div>
                <div className="comet-body-s mb-3">
                  2. To evaluate a specific prompt against a dataset:
                </div>
                <CodeHighlighter
                  data={codeSnippets[exampleTab.value].code}
                  copyData={codeSnippets[exampleTab.value].codeToCopy}
                />
              </div>
            </div>
          )}
          {exampleTab.value === "evaluate-llm" && (
            <div className="flex flex-col gap-6 rounded-md border bg-white p-6">
              <div>
                <div className="comet-body-s mb-3">
                  1. Install Opik using pip from the command line.
                </div>
                <div className="min-h-7">
                  <CodeHighlighter data="pip install opik" />
                </div>
              </div>
              <div>
                <div className="comet-body-s mb-3">
                  2. For more complex evaluation scenarios where you need custom
                  processing:
                </div>
                <CodeHighlighter
                  data={codeSnippets[exampleTab.value].code}
                  copyData={codeSnippets[exampleTab.value].codeToCopy}
                />
              </div>
            </div>
          )}
          {exampleTab.value === "using-playground" && (
            <div className="flex flex-col gap-6 rounded-md border bg-white p-6">
              <div className="comet-body-s">
                To use the Playground, you will need to navigate to the{" "}
                <SheetClose asChild>
                  <Button
                    size="sm"
                    variant="link"
                    className="inline-flex h-auto px-0"
                    asChild
                  >
                    <Link
                      to="/$workspaceName/playground"
                      params={{ workspaceName }}
                    >
                      Playground
                    </Link>
                  </Button>
                </SheetClose>{" "}
                page and:
                <div className="pt-1">
                  1. Configure the LLM provider you want to use
                </div>
                <div className="pt-1">
                  2. Enter the prompts you want to evaluate - You should include
                  variables in the prompts using the{" "}
                  <span className="text-emerald-500">{"{{ variable }}"}</span>{" "}
                  syntax
                </div>
                <div className="pt-1">
                  3. Select the dataset you want to evaluate on
                </div>
                <div className="pt-1">
                  4. Click on the{" "}
                  <span className="text-emerald-500">Evaluate</span> button
                </div>
              </div>
            </div>
          )}
        </div>

        <div className="flex w-[250px] shrink-0 flex-col gap-6 self-start">
          <ApiKeyCard />
        </div>
      </div>
    </div>
  );
};

export default EvaluationExamples;
