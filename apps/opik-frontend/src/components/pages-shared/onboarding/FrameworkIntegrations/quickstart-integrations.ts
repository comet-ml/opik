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
import geminiLogoUrl from "/images/integrations/gemini.png";
import groqLogoUrl from "/images/integrations/groq.png";

import functionDecoratorsCode from "./integration-scripts/FunctionDecorators.py?raw";
import openAiCode from "./integration-scripts/OpenAI.py?raw";
import anthropicCode from "./integration-scripts/Anthropic.py?raw";
import bedrockCode from "./integration-scripts/Bedrock.py?raw";
import geminiCode from "./integration-scripts/Gemini.py?raw";
import langChainCode from "./integration-scripts/LangChain.py?raw";
import langGraphCode from "./integration-scripts/LangGraph.py?raw";
import llamaIndexCode from "./integration-scripts/LlamaIndex.py?raw";
import haystackCode from "./integration-scripts/Haystack.py?raw";
import liteLLMCode from "./integration-scripts/LiteLLM.py?raw";
import ragasCode from "./integration-scripts/Ragas.py?raw";
import groqCode from "./integration-scripts/Groq.py?raw";
import dspyCode from "./integration-scripts/DSPy.py?raw";
import { FrameworkIntegration } from "./types";

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
    code: functionDecoratorsCode,
  },
  {
    label: "OpenAI",
    logo: openAILogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/openai.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/openai"),
    code: openAiCode,
  },
  {
    label: "Anthropic",
    logo: anthropicLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/anthropic.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/openai"),
    code: anthropicCode,
  },
  {
    label: "Bedrock",
    logo: bedrockLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/bedrock.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/openai"),
    code: bedrockCode,
  },
  {
    label: "Gemini",
    logo: geminiLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/gemini.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/openai"),
    code: geminiCode,
  },
  {
    label: "LangChain",
    logo: langChainLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/langchain.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/langchain"),
    code: langChainCode,
  },
  {
    label: "LangGraph",
    logo: langGraphLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/langgraph.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/langchain"),
    code: langGraphCode,
  },
  {
    label: "LlamaIndex",
    logo: llamaIndexLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/llama-index.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/langchain"),
    code: llamaIndexCode,
  },
  {
    label: "Haystack",
    logo: haystackLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/haystack.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/langchain"),
    code: haystackCode,
  },
  {
    label: "LiteLLM",
    logo: liteLLMLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/litellm.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/litellm"),
    code: liteLLMCode,
  },
  {
    label: "Ragas",
    logo: ragasLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/ragas.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/ragas"),
    code: ragasCode,
  },
  {
    label: "Groq",
    logo: groqLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/groq.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/ragas"),
    code: groqCode,
  },
  {
    label: "DSPy",
    logo: dspyLogoUrl,
    colab:
      "https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/dspy.ipynb",
    documentation: buildDocsUrl("/tracing/integrations/ragas"),
    code: dspyCode,
  },
];
