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
import difyLogoUrl from "/images/integrations/dify.png";
import googleAdkLogoUrl from "/images/integrations/google-adk.png";
import guardrailsaiLogoUrl from "/images/integrations/guardrailsai.png";
import ollamaLogoUrl from "/images/integrations/ollama.png";
import openrouterLogoUrl from "/images/integrations/openrouter.png";
import predibaseLogoUrl from "/images/integrations/predibase.png";
import pydanticaiLogoUrl from "/images/integrations/pydanticai.png";
import smolagentsLogoUrl from "/images/integrations/smolagents.png";
import strandsAgentsLogoUrl from "/images/integrations/strands-agents.png";
import vercelAiLogoUrl from "/images/integrations/vercel-ai.png";
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

export type Integration = {
  id: string;
  title: string;
  description: string;
  category: string;
  icon: string;
  code: string;
  tag?: string;
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
    description: "Category placeholder",
    category: INTEGRATION_CATEGORIES.ALL,
    icon: pythonLogoUrl,
    code: functionDecoratorsCode,
  },
  {
    id: "openai",
    title: "OpenAI",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: openAILogoUrl,
    code: openAiCode,
  },
  {
    id: "optimize-openai",
    title: "Optimize OpenAI",
    description: "Agent optimization",
    category: INTEGRATION_CATEGORIES.AGENTS_OPTIMIZATION,
    icon: openAILogoUrl,
    code: "# OpenAI optimization code coming soon",
    isHidden: true,
  },
  {
    id: "anthropic",
    title: "Anthropic",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: anthropicLogoUrl,
    code: anthropicCode,
  },

  {
    id: "bedrock",
    title: "Bedrock",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: bedrockLogoUrl,
    code: bedrockCode,
  },
  {
    id: "gemini",
    title: "Gemini",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: geminiLogoUrl,
    code: geminiCode,
  },
  {
    id: "ollama",
    title: "Ollama",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: ollamaLogoUrl,
    code: "# Ollama integration code coming soon",
    isHidden: true,
  },
  {
    id: "langchain",
    title: "LangChain",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: langChainLogoUrl,
    code: langChainCode,
  },

  {
    id: "langgraph",
    title: "LangGraph",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: langGraphLogoUrl,
    code: langGraphCode,
  },
  {
    id: "llamaindex",
    title: "LlamaIndex",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: llamaIndexLogoUrl,
    code: llamaIndexCode,
  },
  {
    id: "haystack",
    title: "Haystack",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: haystackLogoUrl,
    code: haystackCode,
  },
  {
    id: "litellm",
    title: "LiteLLM",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: liteLLMLogoUrl,
    code: liteLLMCode,
  },

  {
    id: "crewai",
    title: "CrewAI",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: crewaiLogoUrl,
    code: "# CrewAI integration code coming soon",
    isHidden: true,
  },
  {
    id: "dspy",
    title: "DSPy",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: dspyLogoUrl,
    code: dspyCode,
  },
  {
    id: "ragas",
    title: "Ragas",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: ragasLogoUrl,
    code: ragasCode,
  },
  {
    id: "groq",
    title: "Groq",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: groqLogoUrl,
    code: groqCode,
  },

  {
    id: "google-adk",
    title: "Google ADK",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: googleAdkLogoUrl,
    code: "# Google ADK integration code coming soon",
    isHidden: true,
  },
  {
    id: "optimize-google-adk",
    title: "Optimize Google ADK",
    description: "Agent optimization",
    category: INTEGRATION_CATEGORIES.AGENTS_OPTIMIZATION,
    icon: googleAdkLogoUrl,
    code: "# Google ADK optimization code coming soon",
    isHidden: true,
  },
  {
    id: "openrouter",
    title: "OpenRouter",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: openrouterLogoUrl,
    code: "# OpenRouter integration code coming soon",
    isHidden: true,
  },
  {
    id: "autogen",
    title: "AutoGen",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: autogenLogoUrl,
    code: "# AutoGen integration code coming soon",
    isHidden: true,
  },

  {
    id: "agno",
    title: "Agno",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: agnoLogoUrl,
    code: "# Agno integration code coming soon",
    isHidden: true,
  },
  {
    id: "deepseek",
    title: "DeepSeek",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: deepseekLogoUrl,
    code: "# DeepSeek integration code coming soon",
    isHidden: true,
  },
  {
    id: "dify",
    title: "Dify",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: difyLogoUrl,
    code: "# Dify integration code coming soon",
    isHidden: true,
  },
  {
    id: "guardrailsai",
    title: "GuardrailsAI",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: guardrailsaiLogoUrl,
    code: "# GuardrailsAI integration code coming soon",
    isHidden: true,
  },

  {
    id: "predibase",
    title: "Predibase",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: predibaseLogoUrl,
    code: "# Predibase integration code coming soon",
    isHidden: true,
  },
  {
    id: "pydanticai",
    title: "PydanticAI",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: pydanticaiLogoUrl,
    code: "# PydanticAI integration code coming soon",
    isHidden: true,
  },
  {
    id: "smolagents",
    title: "Smolagents",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: smolagentsLogoUrl,
    code: "# Smolagents integration code coming soon",
    isHidden: true,
  },
  {
    id: "strands-agents",
    title: "Strands Agents",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: strandsAgentsLogoUrl,
    code: "# Strands Agents integration code coming soon",
    isHidden: true,
  },

  {
    id: "vercel-ai",
    title: "Vercel AI",
    description: "Frameworks & tools",
    category: INTEGRATION_CATEGORIES.FRAMEWORKS_TOOLS,
    icon: vercelAiLogoUrl,
    code: "# Vercel AI integration code coming soon",
    isHidden: true,
  },
  {
    id: "watsonx",
    title: "WatsonX",
    description: "LLM provider",
    category: INTEGRATION_CATEGORIES.LLM_PROVIDERS,
    icon: watsonxLogoUrl,
    code: "# WatsonX integration code coming soon",
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
