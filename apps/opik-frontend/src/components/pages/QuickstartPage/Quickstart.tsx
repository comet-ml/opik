import React, { useMemo, useState } from "react";
import { Link, useRouter } from "@tanstack/react-router";
import { MoveLeft, SquareArrowOutUpRight } from "lucide-react";

import useAppStore from "@/store/AppStore";
import { Button } from "@/components/ui/button";
import ApiKeyInput from "@/components/shared/ApiKeyInput/ApiKeyInput";
import pythonLogoUrl from "/images/integrations/python.png";
import langChainLogoUrl from "/images/integrations/langchain.png";
import liteLLMLogoUrl from "/images/integrations/litellm.png";
import openAILogoUrl from "/images/integrations/openai.png";
import ragasLogoUrl from "/images/integrations/ragas.png";
import colabLogo from "/images/colab-logo.png";
import { buildDocsUrl } from "@/lib/utils";
import { IntegrationComponentProps } from "@/components/pages/QuickstartPage/integrations/types";
import FunctionDecorators from "@/components/pages/QuickstartPage/integrations/FunctionDecorators";
import LangChain from "@/components/pages/QuickstartPage/integrations/LangChain";
import LiteLLM from "@/components/pages/QuickstartPage/integrations/LiteLLM";
import OpenAI from "@/components/pages/QuickstartPage/integrations/OpenAI";
import Ragas from "@/components/pages/QuickstartPage/integrations/Ragas";

type Integration = {
  label: string;
  logo: string;
  colab: string;
  documentation: string;
  component: React.FC<IntegrationComponentProps>;
};

const INTEGRATIONS: Integration[] = [
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

type QuickstartProps = {
  apiKey?: string;
  showColabLinks?: boolean;
};

const Quickstart: React.FunctionComponent<QuickstartProps> = ({
  apiKey,
  showColabLinks = true,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [integrationIndex, setIntegrationIndex] = useState<number>(0);
  const integration = useMemo(
    () => INTEGRATIONS[integrationIndex],
    [integrationIndex],
  );

  const router = useRouter();

  const renderMenuItems = () => {
    return INTEGRATIONS.map((item, index) => {
      return (
        <li
          key={item.label}
          className="comet-body-s flex h-10 w-full cursor-pointer items-center gap-2 rounded-md pl-2 pr-4 text-foreground hover:bg-primary-foreground data-[status=active]:bg-primary-100"
          onClick={() => setIntegrationIndex(index)}
          data-status={index === integrationIndex ? "active" : "inactive"}
        >
          <img
            alt={item.label}
            src={item.logo}
            className="size-[22px] shrink-0"
          />
          <div className="ml-1 truncate">{item.label}</div>
        </li>
      );
    });
  };

  return (
    <div className="flex w-full min-w-fit pb-12 pt-5">
      <div className="sticky top-5 self-start">
        <Button
          variant="link"
          size="sm"
          className="absolute left-0 top-1"
          onClick={() => router.history.back()}
        >
          <MoveLeft className="mr-2 size-4 shrink-0"></MoveLeft>
          Return back
        </Button>
      </div>
      <div className="m-auto flex w-full max-w-[1250px] gap-8">
        <div className="sticky top-20 mt-[184px] flex w-[200px] shrink-0 flex-col gap-4 self-start">
          <ul className="flex flex-col gap-2">{renderMenuItems()}</ul>
          <Button variant="ghost" asChild>
            <a
              href={buildDocsUrl("/tracing/integrations/overview")}
              target="_blank"
              rel="noreferrer"
            >
              Explore all integrations
              <SquareArrowOutUpRight className="ml-2 size-4 shrink-0" />
            </a>
          </Button>
        </div>
        <div className="flex flex-1 flex-col">
          <div className="sticky top-5 self-end">
            <Link to="/$workspaceName/home" params={{ workspaceName }}>
              <Button variant="secondary">
                Explore the platform on my own
              </Button>
            </Link>
          </div>
          <div className="flex min-w-[650px] flex-1 gap-8">
            <div className="flex flex-1">
              <div className="flex w-full flex-col">
                <h1 className="comet-title-xl text-center">Quickstart guide</h1>
                <div className="comet-body-s mt-4 h-[92px] w-[468px] self-center text-center text-muted-slate">
                  Select the framework and follow the instructions to integrate
                  Opik with your own code or use our ready-to-run examples on
                  the right.
                </div>
                <integration.component apiKey={apiKey} />
              </div>
            </div>

            <div className="sticky top-20 mt-[144px] flex w-[280px] shrink-0 flex-col gap-6 self-start">
              <div className="flex flex-1 flex-col justify-between gap-6 rounded-md border bg-white px-8 pb-6 pt-8">
                <div className="comet-title-xs text-foreground-secondary">
                  Try one of our full examples
                </div>
                {showColabLinks ? (
                  <div>
                    <div className="comet-body-s mb-4 text-muted-slate">
                      Or try this end to end example in Google Colab:
                    </div>
                    <Button
                      variant="outline"
                      asChild
                      className="w-full justify-between"
                    >
                      <a
                        href={integration.colab}
                        target="_blank"
                        rel="noreferrer"
                      >
                        <div className="flex items-center gap-1">
                          Open in Colab
                          <img
                            src={colabLogo}
                            alt="colab logo"
                            className="h-[27px] w-8"
                          />
                        </div>

                        <SquareArrowOutUpRight className="ml-2 size-4 shrink-0" />
                      </a>
                    </Button>
                  </div>
                ) : null}
                <div>
                  <Button variant="link" size="sm" asChild className="px-0">
                    <a
                      href={integration.documentation}
                      target="_blank"
                      rel="noreferrer"
                    >
                      Explore the documentation
                    </a>
                  </Button>
                </div>
              </div>
              {apiKey && (
                <div className="flex flex-1 flex-col justify-between gap-4 rounded-md border bg-white px-8 pb-8 pt-6">
                  <div className="comet-title-xs text-foreground-secondary">
                    Copy your API key
                  </div>
                  <ApiKeyInput apiKey={apiKey} />
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default Quickstart;
