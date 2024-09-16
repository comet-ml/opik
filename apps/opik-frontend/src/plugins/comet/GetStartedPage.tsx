import CopyButton from "@/components/shared/CopyButton/CopyButton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import useAppStore from "@/store/AppStore";
import { Link } from "@tanstack/react-router";
import { KeyRound, MoveRight, SquareArrowOutUpRight } from "lucide-react";
import useUser from "./useUser";
import { buildUrl } from "./utils";
import demoProjectImageUrl from "/images/demo-project.png";
import langChainLogoUrl from "/images/integrations/langchain.png";
import liteLLMLogoUrl from "/images/integrations/litellm.png";
import openAILogoUrl from "/images/integrations/openai.png";
import pythonLogoUrl from "/images/integrations/python.png";
import ragasLogoUrl from "/images/integrations/ragas.png";

const LOGO_IMAGES = [
  pythonLogoUrl,
  liteLLMLogoUrl,
  openAILogoUrl,
  ragasLogoUrl,
  langChainLogoUrl,
];

const GetStartedPage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data: user } = useUser();

  if (!user) return;

  const apiKey = user.apiKeys[0];

  return (
    <div className="flex w-full justify-center px-4 pb-2 pt-12">
      <div className="flex max-w-[1200px] flex-col gap-8">
        <div className="flex flex-col gap-3">
          <div className="flex flex-row justify-between">
            <div className="flex flex-col gap-3">
              <div className="comet-body-s text-muted-slate">
                Welcome {user.userName}
              </div>

              <h1 className="comet-title-l text-foreground-secondary">
                Get Started with Opik
              </h1>
            </div>

            <Link to="/$workspaceName/projects" params={{ workspaceName }}>
              <Button variant="secondary">
                Explore the platform on my own
              </Button>
            </Link>
          </div>

          <div className="comet-body-s text-muted-slate">
            Start with one of our integrations or a few lines of code to log,
            view and evaluate your LLM traces during both development and
            production.
          </div>
        </div>

        <div className="flex w-full flex-col gap-14">
          <div className="flex w-full flex-row gap-5">
            <div className="flex min-w-[65%] flex-1 flex-row justify-between gap-20 rounded-md border bg-white px-8 py-10">
              <div className="flex flex-col gap-4">
                <div className="comet-title-s text-nowrap text-foreground-secondary">
                  Integrate Opik
                </div>
                <div className="comet-body-s text-muted-slate">
                  A step by step guide for adding Opik into your existing
                  application. Includes detailed examples.
                </div>
                <div>
                  <a
                    href={buildUrl("docs/opik/")}
                    target="_blank"
                    rel="noreferrer"
                  >
                    <Button>
                      To the Quickstart guide
                      <SquareArrowOutUpRight className="ml-2 size-4" />
                    </Button>
                  </a>
                </div>
              </div>

              <div className="flex min-w-[180px] flex-row flex-wrap content-start items-center gap-3 self-center">
                {LOGO_IMAGES.map((url) => {
                  return <img className="size-[50px]" key={url} src={url} />;
                })}
              </div>
            </div>

            <div className="flex flex-1 flex-col justify-between gap-5 rounded-md border bg-white px-8 py-10">
              <div className="flex flex-col gap-4">
                <div className="comet-title-s text-nowrap text-foreground-secondary">
                  Copy your API key
                </div>
                <div className="comet-body-s text-muted-slate">
                  API keys are used to send traces to the Opik platform.
                </div>
              </div>

              <div className="flex flex-row items-center">
                <KeyRound className="mr-3 size-6" />
                <Input
                  className="mr-2"
                  readOnly
                  value={`${apiKey.substring(0, 6)}*******************`}
                />
                <CopyButton
                  message="Successfully copied API key"
                  text={apiKey}
                  tooltipText="Copy API key"
                />
              </div>
            </div>
          </div>

          <div className="flex flex-col gap-5">
            <div className="comet-body-accented">Explore demo project</div>

            <Link
              className="flex cursor-pointer flex-row justify-between rounded-md border bg-white lg:w-[65%]"
              to="/$workspaceName/projects"
              params={{ workspaceName }}
              target="_blank"
            >
              <img
                className="min-w-[340px] object-cover"
                src={demoProjectImageUrl}
              />
              <div className="flex flex-col justify-between px-8 py-10">
                <div className="flex flex-col gap-4">
                  <div className="comet-title-s text-foreground-secondary">
                    SQL query generation
                  </div>
                  <div className="comet-body-s text-muted-slate">
                    Perform a text to SQL query generation using LangChain. The
                    example uses the Chinook database of a music store, with
                    both employee, customer and invoice data.
                  </div>
                </div>
                <div className="comet-body-s flex flex-row items-center justify-end text-[#5155F5]">
                  Explore project <MoveRight className="ml-2 size-4" />
                </div>
              </div>
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
};

export default GetStartedPage;
