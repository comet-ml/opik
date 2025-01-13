import { buildDocsUrl } from "@/lib/utils";
import pythonLogoUrl from "/images/integrations/python.png";
import langChainLogoUrl from "/images/integrations/langchain.png";
import liteLLMLogoUrl from "/images/integrations/litellm.png";
import openAILogoUrl from "/images/integrations/openai.png";
import anthropicLogoUrl from "/images/integrations/anthropic.png";
import bedrockLogoUrl from "/images/integrations/bedrock.png";
import ragasLogoUrl from "/images/integrations/ragas.png";
import langGraphLogoUrl from "/images/integrations/langgraph.png";
import llamaIndexLogoUrl from "/images/integrations/llamaindex.png";
import haystackLogoUrl from "/images/integrations/haystack.png";
import dspyLogoUrl from "/images/integrations/dspy.png";
import FunctionDecorators from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/FunctionDecorators";
import LangChain from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/LangChain";
import LiteLLM from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/LiteLLM";
import OpenAI from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/OpenAI";
import Ragas from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/Ragas";
import { FrameworkIntegration } from "@/components/pages-shared/onboarding/FrameworkIntegrations/types";
import Anthropic from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/Anthropic";
import Bedrock from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/Bedrock";
import LangGraph from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/LangGraph";
import LlamaIndex from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/LlamaIndex";
import Haystack from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/Haystack";
import DSPy from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/DSPy";

export const QUICKSTART_INTEGRATIONS: FrameworkIntegration[] = [
  {
    label: "Function decorators",
    logo: pythonLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/quickstart_notebook.ipynb",
    documentation: buildDocsUrl(
      "/tracing/log_traces",
      "#using-function-decorators",
    ),
    component: FunctionDecorators,
  },
  {
    label: "OpenAI",
    logo: openAILogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/openai.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/openai"),
    component: OpenAI,
  },
  {
    label: "Anthropic",
    logo: anthropicLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/anthropic.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/openai"),
    component: Anthropic,
  },
  {
    label: "Bedrock",
    logo: bedrockLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/bedrock.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/openai"),
    component: Bedrock,
  },
  {
    label: "LangChain",
    logo: langChainLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/langchain.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/langchain"),
    component: LangChain,
  },
  {
    label: "LangGraph",
    logo: langGraphLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/langgraph.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/langchain"),
    component: LangGraph,
  },
  {
    label: "LlamaIndex",
    logo: llamaIndexLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/llama-index.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/langchain"),
    component: LlamaIndex,
  },
  {
    label: "Haystack",
    logo: haystackLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/haystack.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/langchain"),
    component: Haystack,
  },
  {
    label: "LiteLLM",
    logo: liteLLMLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/litellm.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/litellm"),
    component: LiteLLM,
  },
  {
    label: "Ragas",
    logo: ragasLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/ragas.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/ragas"),
    component: Ragas,
  },
  {
    label: "DSPy",
    logo: dspyLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/dspy.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/ragas"),
    component: DSPy,
  },
];
