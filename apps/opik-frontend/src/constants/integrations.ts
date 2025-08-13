import pythonLogoUrl from "/images/integrations/python.png";
import openAILogoUrl from "/images/integrations/openai.png";
import anthropicLogoUrl from "/images/integrations/anthropic.png";
import bedrockLogoUrl from "/images/integrations/bedrock.png";
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
import openrouterLogoUrl from "/images/integrations/openrouter.png";
import predibaseLogoUrl from "/images/integrations/predibase.png";
import pydanticaiLogoUrl from "/images/integrations/pydanticai.png";
import smolagentsLogoUrl from "/images/integrations/smolagents.png";
import strandsAgentsLogoUrl from "/images/integrations/strands-agents.png";
// import vercelAiLogoUrl from "/images/integrations/vercel-ai.png";
import watsonxLogoUrl from "/images/integrations/watsonx.png";

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
import pydanticaiCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/PydanticAI.py?raw";
import smolagentsCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/Smolagents.py?raw";
import strandsAgentsCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/StrandsAgents.py?raw";
// import vercelAiCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/VercelAI.py?raw";
import watsonxCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/WatsonX.py?raw";
// import openAIAgentsCode from "@/components/pages-shared/onboarding/FrameworkIntegrations/integration-scripts/OpenAIAgents.py?raw";

export type Integration = {
  id: string;
  title: string;
  description?: string;
  category: string;
  icon: string;
  code: string;
  tag?: string;
  installCommand: string;
  isHidden?: boolean;
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
  },
  {
    id: "openai",
    title: "OpenAI",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: openAILogoUrl,
    code: openAiCode,
    installCommand: "pip install -U opik openai",
  },
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
  },

  {
    id: "bedrock",
    title: "Bedrock",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: bedrockLogoUrl,
    code: bedrockCode,
    installCommand: "pip install -U opik boto3",
  },
  {
    id: "gemini",
    title: "Gemini",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: geminiLogoUrl,
    code: geminiCode,
    installCommand: "pip install -U opik google-genai",
  },
  {
    id: "ollama",
    title: "Ollama",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: ollamaLogoUrl,
    code: ollamaCode,
    installCommand: "pip install -U opik ollama",
    isHidden: true,
  },
  {
    id: "langchain",
    title: "LangChain",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: langChainLogoUrl,
    code: langChainCode,
    installCommand: "pip install -U opik langchain langchain_openai",
  },

  {
    id: "langgraph",
    title: "LangGraph",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: langGraphLogoUrl,
    code: langGraphCode,
    installCommand: "pip install -U opik langgraph langchain",
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
  },
  {
    id: "haystack",
    title: "Haystack",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: haystackLogoUrl,
    code: haystackCode,
    installCommand: "pip install -U opik haystack-ai",
  },
  {
    id: "litellm",
    title: "LiteLLM",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: liteLLMLogoUrl,
    code: liteLLMCode,
    installCommand: "pip install -U opik litellm",
  },

  {
    id: "crewai",
    title: "CrewAI",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: crewaiLogoUrl,
    code: crewaiCode,
    installCommand: "pip install -U opik crewai crewai-tools",
    isHidden: true,
  },
  {
    id: "dspy",
    title: "DSPy",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: dspyLogoUrl,
    code: dspyCode,
    installCommand: "pip install -U opik dspy",
  },
  {
    id: "ragas",
    title: "Ragas",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: ragasLogoUrl,
    code: ragasCode,
    installCommand: "pip install -U opik ragas",
  },
  {
    id: "groq",
    title: "Groq",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: groqLogoUrl,
    code: groqCode,
    installCommand: "pip install -U opik litellm",
  },

  {
    id: "google-adk",
    title: "Google ADK",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: googleAdkLogoUrl,
    code: adkCode,
    installCommand: "pip install -U opik google-adk litellm",
    isHidden: true,
  },
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
    code: openrouterCode,
    installCommand: "pip install -U opik openai",
    isHidden: true,
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
    isHidden: true,
  },

  {
    id: "agno",
    title: "Agno",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: agnoLogoUrl,
    code: agnoCode,
    installCommand:
      "pip install -U agno openai opentelemetry-sdk opentelemetry-exporter-otlp openinference-instrumentation-agno yfinance",
    isHidden: true,
  },
  {
    id: "deepseek",
    title: "DeepSeek",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: deepseekLogoUrl,
    code: deepseekCode,
    installCommand: "pip install -U opik openai",
    isHidden: true,
  },
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
      "pip install -U opik guardrails-ai \nguardrails hub install hub://guardrails/politeness_check",
    isHidden: true,
  },

  {
    id: "predibase",
    title: "Predibase",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: predibaseLogoUrl,
    code: predibaseCode,
    installCommand: "pip install -U opik predibase langchain",
    isHidden: true,
  },
  {
    id: "pydanticai",
    title: "PydanticAI",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: pydanticaiLogoUrl,
    code: pydanticaiCode,
    installCommand:
      "pip install --upgrade --quiet pydantic-ai logfire 'logfire[httpx]'",
    isHidden: true,
  },
  {
    id: "smolagents",
    title: "Smolagents",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: smolagentsLogoUrl,
    code: smolagentsCode,
    installCommand: "pip install -U opik 'smolagents[telemetry,toolkit]'",
    isHidden: true,
  },
  {
    id: "strands-agents",
    title: "Strands Agents",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: strandsAgentsLogoUrl,
    code: strandsAgentsCode,
    installCommand: "pip install -U 'strands-agents' 'strands-agents-tools'",
    isHidden: true,
  },
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
  {
    id: "watsonx",
    title: "WatsonX",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: watsonxLogoUrl,
    code: watsonxCode,
    installCommand: "pip install -U opik litellm",
    isHidden: true,
  },
];

export const getIntegrationsByCategory = (category: string): Integration[] => {
  if (category === INTEGRATION_CATEGORIES.ALL) {
    return INTEGRATIONS;
  }
  return INTEGRATIONS.filter(
    (integration) => integration.category === category,
  );
};
