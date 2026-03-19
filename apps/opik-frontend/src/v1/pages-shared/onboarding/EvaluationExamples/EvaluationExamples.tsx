import React, { useState } from "react";
import ApiKeyCard from "../ApiKeyCard/ApiKeyCard";
import evaluatePromptsCode from "./evaluation-scripts/EvaluatePrompts.py?raw";
import evaluateLLMCode from "./evaluation-scripts/EvaluateLLM.py?raw";
import { Blocks, FileTerminal, FlaskConical, LucideIcon } from "lucide-react";
import IntegrationListLayout from "../IntegrationListLayout/IntegrationListLayout";
import IntegrationTabs from "../IntegrationTabs/IntegrationTabs";
import IntegrationTemplate from "../FrameworkIntegrations/IntegrationTemplate";
import UsingPlaygroundTab from "./UsingPlaygroundTab";
import { useUserApiKey } from "@/store/AppStore";

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

const EvaluationExamples: React.FC = () => {
  const [exampleTab, setExampleTab] = useState<TabValue>(tabList[0].value);
  const apiKey = useUserApiKey();

  const tabContentMap: Record<TabValue, JSX.Element> = {
    "evaluate-prompts": (
      <IntegrationTemplate code={evaluatePromptsCode} apiKey={apiKey} />
    ),
    "evaluate-llm": (
      <IntegrationTemplate code={evaluateLLMCode} apiKey={apiKey} />
    ),
    "using-playground": <UsingPlaygroundTab />,
  };

  return (
    <IntegrationListLayout
      leftSidebar={
        <>
          <IntegrationTabs.Title>Choose a Method</IntegrationTabs.Title>
          <IntegrationTabs>
            {tabList.map((item) => (
              <IntegrationTabs.Item
                key={item.label}
                onClick={() => setExampleTab(item.value)}
                isActive={item.value === exampleTab}
              >
                <item.icon className="size-4 shrink-0" />
                <div className="ml-1 truncate">{item.label}</div>
              </IntegrationTabs.Item>
            ))}
          </IntegrationTabs>
        </>
      }
      rightSidebar={<ApiKeyCard />}
    >
      {tabContentMap[exampleTab]}
    </IntegrationListLayout>
  );
};

export default EvaluationExamples;
