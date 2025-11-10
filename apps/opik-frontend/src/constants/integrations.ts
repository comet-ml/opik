import pythonLogoUrl from "/images/integrations/python.png";
import openAILogoUrl from "/images/integrations/openai.png";
import openAIWhiteLogoUrl from "/images/integrations/openai-white.png";
import anthropicLogoUrl from "/images/integrations/anthropic.png";
import bedrockLogoUrl from "/images/integrations/bedrock.png";
import bedrockWhiteLogoUrl from "/images/integrations/bedrock-white.png";
import geminiLogoUrl from "/images/integrations/gemini.png";
import groqLogoUrl from "/images/integrations/groq.png";
import langChainLogoUrl from "/images/integrations/langchain.png";
import langGraphLogoUrl from "/images/integrations/langgraph.png";
import llamaIndexLogoUrl from "/images/integrations/llamaindex.png";
import haystackLogoUrl from "/images/integrations/haystack.png";
import liteLLMLogoUrl from "/images/integrations/litellm.png";
import ragasLogoUrl from "/images/integrations/ragas.png";
import dspyLogoUrl from "/images/integrations/dspy.png";

import agnoLogoUrl from "/images/integrations/agno.png";
import autogenLogoUrl from "/images/integrations/autogen.png";
import crewaiLogoUrl from "/images/integrations/crewai.png";
import deepseekLogoUrl from "/images/integrations/deepseek.png";
// import difyLogoUrl from "/images/integrations/dify.png";
import googleAdkLogoUrl from "/images/integrations/google-adk.png";
import guardrailsaiLogoUrl from "/images/integrations/guardrailsai.png";
import ollamaLogoUrl from "/images/integrations/ollama.png";
import ollamaWhiteLogoUrl from "/images/integrations/ollama-white.png";
import openrouterLogoUrl from "/images/integrations/openrouter.png";
import openrouterWhiteLogoUrl from "/images/integrations/openrouter-white.png";
import predibaseLogoUrl from "/images/integrations/predibase.png";
// import pydanticaiLogoUrl from "/images/integrations/pydanticai.png";
// import smolagentsLogoUrl from "/images/integrations/smolagents.png";
// import strandsAgentsLogoUrl from "/images/integrations/strands-agents.png";
// import vercelAiLogoUrl from "/images/integrations/vercel-ai.png";
// import watsonxLogoUrl from "/images/integrations/watsonx.png";

import functionDecoratorsCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/FunctionDecorators.py?raw";
import openAiCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/OpenAI.py?raw";
import anthropicCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/Anthropic.py?raw";
import bedrockCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/Bedrock.py?raw";
import geminiCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/Gemini.py?raw";
import groqCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/Groq.py?raw";
import langChainCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/LangChain.py?raw";
import langGraphCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/LangGraph.py?raw";
import llamaIndexCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/LlamaIndex.py?raw";
import haystackCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/Haystack.py?raw";
import liteLLMCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/LiteLLM.py?raw";
import ragasCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/Ragas.py?raw";
import dspyCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/DSPy.py?raw";

import { integrationLogsMap } from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-logs";

import ollamaCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/Ollama.py?raw";
import crewaiCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/CrewAI.py?raw";
import adkCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/ADK.py?raw";
import openrouterCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/OpenRouter.py?raw";
import autogenCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/AutoGen.py?raw";
import agnoCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/Agno.py?raw";
import deepseekCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/DeepSeek.py?raw";
// import difyCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/Dify.py?raw";
import guardrailsaiCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/GuardrailsAI.py?raw";
import predibaseCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/Predibase.py?raw";
// import pydanticaiCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/PydanticAI.py?raw";
// import smolagentsCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/Smolagents.py?raw";
import { buildDocsUrl } from "@/lib/utils";
// import strandsAgentsCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/StrandsAgents.py?raw";
// import vercelAiCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/VercelAI.py?raw";
// import watsonxCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/WatsonX.py?raw";
// import openAIAgentsCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/OpenAIAgents.py?raw";

export type Integration = {
  id: string;
  title: string;
  description?: string;
  category: string;
  icon: string;
  whiteIcon?: string;
  code: string;
  tag?: string;
  installCommand: string;
  docsLink: string;
  executionUrl?: string;
  executionLogs?: string[];
};

export const INTEGRATION_CATEGORIES = {
  ALL: "All integrations",
  LLM_PROVIDERS: "LLM providers",
  FRAMEWORKS_TOOLS: "Frameworks & tools",
  AGENTS_OPTIMIZATION: "Agents optimization",
} as const;

export const INTEGRATIONS: Integration[] = [
  {
    id: "function-decorators",
    title: "Function decorators",
    category: INTEGRATION_CATEGORIES.ALL,
    icon: pythonLogoUrl,
    code: functionDecoratorsCode,
    installCommand: "pip install -U opik",
    docsLink: buildDocsUrl("/tracing/log_traces"),
  },
  {
    id: "openai",
    title: "OpenAI",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: openAILogoUrl,
    whiteIcon: openAIWhiteLogoUrl,
    code: openAiCode,
    installCommand: "pip install -U opik openai",
    docsLink: buildDocsUrl("/integrations/openai"),
    executionUrl: "openai/run_stream",
    executionLogs: integrationLogsMap.OpenAI,
  },
  // TODO: Code snippet required
  // {
  //   id: "optimize-openai",
  //   title: "Optimize OpenAI",
  //   description: "Agent optimization",
  //   category: INTEGRATION_CATEGORIES.AGENTS_OPTIMIZATION,
  //   icon: openAILogoUrl,
  //   code: openAIAgentsCode,
  //   installCommand: "pip install opik openai-agents",
  //   isHidden: true,
  // },
  {
    id: "anthropic",
    title: "Anthropic",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: anthropicLogoUrl,
    code: anthropicCode,
    installCommand: "pip install -U opik anthropic",
    docsLink: buildDocsUrl("/integrations/anthropic"),
    executionUrl: "anthropic/run_stream",
    executionLogs: integrationLogsMap.Anthropic,
  },

  {
    id: "bedrock",
    title: "Bedrock",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: bedrockLogoUrl,
    whiteIcon: bedrockWhiteLogoUrl,
    code: bedrockCode,
    installCommand: "pip install -U opik boto3",
    docsLink: buildDocsUrl("/integrations/bedrock"),
  },
  {
    id: "gemini",
    title: "Gemini",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: geminiLogoUrl,
    code: geminiCode,
    installCommand: "pip install -U opik google-genai",
    docsLink: buildDocsUrl("/integrations/gemini"),
    // executionUrl: "gemini/run_stream",
    // executionLogs: integrationLogsMap.Gemini,
  },
  {
    id: "ollama",
    title: "Ollama",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: ollamaLogoUrl,
    whiteIcon: ollamaWhiteLogoUrl,
    code: ollamaCode,
    installCommand: "pip install -U opik ollama",
    docsLink: buildDocsUrl("/integrations/ollama"),
  },
  {
    id: "langchain",
    title: "LangChain",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: langChainLogoUrl,
    code: langChainCode,
    installCommand: "pip install -U opik langchain langchain_openai",
    docsLink: buildDocsUrl("/integrations/langchain"),
  },

  {
    id: "langgraph",
    title: "LangGraph",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: langGraphLogoUrl,
    code: langGraphCode,
    installCommand: "pip install -U opik langgraph langchain",
    docsLink: buildDocsUrl("/integrations/langgraph"),
  },
  {
    id: "llamaindex",
    title: "LlamaIndex",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: llamaIndexLogoUrl,
    code: llamaIndexCode,
    installCommand:
      "pip install -U opik llama-index llama-index-agent-openai llama-index-llms-openai llama-index-callbacks-opik",

    docsLink: buildDocsUrl("/integrations/llama_index"),
  },
  {
    id: "haystack",
    title: "Haystack",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: haystackLogoUrl,
    code: haystackCode,
    installCommand: "pip install -U opik haystack-ai",
    docsLink: buildDocsUrl("/integrations/haystack"),
  },
  {
    id: "litellm",
    title: "LiteLLM",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: liteLLMLogoUrl,
    code: liteLLMCode,
    installCommand: "pip install -U opik litellm",
    docsLink: buildDocsUrl("/integrations/litellm"),
  },

  {
    id: "crewai",
    title: "CrewAI",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: crewaiLogoUrl,
    code: crewaiCode,
    installCommand: "pip install -U opik crewai crewai-tools",
    docsLink: buildDocsUrl("/integrations/crewai"),
  },
  {
    id: "dspy",
    title: "DSPy",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: dspyLogoUrl,
    code: dspyCode,
    installCommand: "pip install -U opik dspy",
    docsLink: buildDocsUrl("/integrations/dspy"),
  },
  {
    id: "ragas",
    title: "Ragas",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: ragasLogoUrl,
    code: ragasCode,
    installCommand: "pip install -U opik ragas",
    docsLink: buildDocsUrl("/integrations/ragas"),
  },
  {
    id: "groq",
    title: "Groq",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: groqLogoUrl,
    code: groqCode,
    installCommand: "pip install -U opik litellm",
    docsLink: buildDocsUrl("/integrations/groq"),
  },

  {
    id: "google-adk",
    title: "Google ADK",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: googleAdkLogoUrl,
    code: adkCode,
    installCommand: "pip install -U opik google-adk litellm",
    docsLink: buildDocsUrl("/integrations/adk"),
  },
  // TODO: Code snippet required
  // {
  //   id: "optimize-google-adk",
  //   title: "Optimize Google ADK",
  //   description: "Agent optimization",
  //   category: INTEGRATION_CATEGORIES.AGENTS_OPTIMIZATION,
  //   icon: googleAdkLogoUrl,
  //   code: "# Google ADK optimization code coming soon",
  //   installCommand: "pip install opik google-adk",
  //   isHidden: true,
  // },
  {
    id: "openrouter",
    title: "OpenRouter",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: openrouterLogoUrl,
    whiteIcon: openrouterWhiteLogoUrl,
    code: openrouterCode,
    installCommand: "pip install -U opik openai",
    docsLink: buildDocsUrl("/integrations/openrouter"),
  },
  {
    id: "autogen",
    title: "AutoGen",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: autogenLogoUrl,
    code: autogenCode,
    installCommand:
      'pip install -U "autogen-agentchat" "autogen-ext[openai]" opik opentelemetry-sdk opentelemetry-instrumentation-openai opentelemetry-exporter-otlp',
    docsLink: buildDocsUrl("/integrations/autogen"),
  },

  {
    id: "agno",
    title: "Agno",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: agnoLogoUrl,
    code: agnoCode,
    installCommand:
      "pip install -U opik agno openai opentelemetry-sdk opentelemetry-exporter-otlp openinference-instrumentation-agno yfinance",
    docsLink: buildDocsUrl("/integrations/agno"),
  },
  {
    id: "deepseek",
    title: "DeepSeek",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: deepseekLogoUrl,
    code: deepseekCode,
    installCommand: "pip install -U opik openai",
    docsLink: buildDocsUrl("/integrations/deepseek"),
  },
  // TODO: custom UI required
  // {
  //   id: "dify",
  //   title: "Dify",
  //   description: "LLM provider",
  //   category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
  //   icon: difyLogoUrl,
  //   code: difyCode,
  //   installCommand: "pip install opik requests",
  //   isHidden: true,
  // },
  {
    id: "guardrailsai",
    title: "GuardrailsAI",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: guardrailsaiLogoUrl,
    code: guardrailsaiCode,
    installCommand:
      "pip install -U opik guardrails-ai \nguardrails configure \nguardrails hub install hub://guardrails/politeness_check",

    docsLink: buildDocsUrl("/integrations/guardrails-ai"),
  },

  {
    id: "predibase",
    title: "Predibase",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: predibaseLogoUrl,
    code: predibaseCode,
    installCommand: "pip install -U opik predibase langchain",
    docsLink: buildDocsUrl("/integrations/predibase"),
  },
  // TODO: Not working, error
  // {
  //   id: "pydanticai",
  //   title: "PydanticAI",
  //   description: "Frameworks & tools",
  //   category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
  //   icon: pydanticaiLogoUrl,
  //   code: pydanticaiCode,
  //   installCommand:
  //     "pip install --upgrade --quiet opik pydantic-ai logfire 'logfire[httpx]'",
  //   isHidden: true,
  // },
  // Replaced with "View all integrations" card in the grid
  // {
  //   id: "smolagents",
  //   title: "Smolagents",
  //   description: "Frameworks & tools",
  //   category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
  //   icon: smolagentsLogoUrl,
  //   code: smolagentsCode,
  //   installCommand: "pip install -U opik 'smolagents[telemetry,toolkit]'",
  //   docsLink: buildDocsUrl("/integrations/smolagents"),
  // },
  // TODO: Outdated code
  // {
  //   id: "strands-agents",
  //   title: "Strands Agents",
  //   description: "Frameworks & tools",
  //   category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
  //   icon: strandsAgentsLogoUrl,
  //   code: strandsAgentsCode,
  //   installCommand: "pip install -U 'strands-agents' 'strands-agents-tools' opentelemetry-exporter-otlp opik",
  //   isHidden: true,
  // },
  // TODO: custom UI required
  // {
  //   id: "vercel-ai",
  //   title: "Vercel AI",
  //   description: "Frameworks & tools",
  //   category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
  //   icon: vercelAiLogoUrl,
  //   code: vercelAiCode,
  //   installCommand: "npm install @opik/ai ai",
  //   isHidden: true,
  // },
  // TODO: Broken code
  // {
  //   id: "watsonx",
  //   title: "WatsonX",
  //   description: "LLM provider",
  //   category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
  //   icon: watsonxLogoUrl,
  //   code: watsonxCode,
  //   installCommand: "pip install -U opik litellm",
  //   isHidden: true,
  // },
];

export const getIntegrationsByCategory = (category: string): Integration[] => {
  if (category === INTEGRATION_CATEGORIES.ALL) {
    return INTEGRATIONS;
  }
  return INTEGRATIONS.filter(
    (integration) => integration.category === category,
  );
};
