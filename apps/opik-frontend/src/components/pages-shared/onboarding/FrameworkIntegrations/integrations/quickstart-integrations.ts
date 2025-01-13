import { buildDocsUrl } from "@/lib/utils";
import pythonLogoUrl from "/images/integrations/python.png";
import langChainLogoUrl from "/images/integrations/langchain.png";
import liteLLMLogoUrl from "/images/integrations/litellm.png";
import openAILogoUrl from "/images/integrations/openai.png";
import ragasLogoUrl from "/images/integrations/ragas.png";
import FunctionDecorators from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/FunctionDecorators";
import LangChain from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/LangChain";
import LiteLLM from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/LiteLLM";
import OpenAI from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/OpenAI";
import Ragas from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/Ragas";
import { FrameworkIntegration } from "@/components/pages-shared/onboarding/FrameworkIntegrations/types";

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
    label: "LangChain",
    logo: langChainLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/langchain.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/langchain"),
    component: LangChain,
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
    label: "OpenAI",
    logo: openAILogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/openai.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/openai"),
    component: OpenAI,
  },
  {
    label: "Ragas",
    logo: ragasLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/ragas.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/ragas"),
    component: Ragas,
  },
];
