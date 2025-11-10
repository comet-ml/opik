import { Button } from "@/components/ui/button";
import { SheetClose } from "@/components/ui/sheet";
import useAppStore from "@/store/AppStore";
import { Link } from "@tanstack/react-router";
import evaluationGifUrl from "/images/playground_evaluation.gif";
import { buildDocsUrl } from "@/lib/utils";

const UsingPlaygroundTab = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <div className="flex flex-col gap-6 rounded-md border bg-background p-6">
      <div className="comet-body-s">
        <div className="pt-1">
          You can run prompt evaluations from the Opik platform using the
          Playground. This allows you to test different prompts on any datasets
          available in the platform.
        </div>

        <img className="my-5 block" src={evaluationGifUrl} />

        <div className="pb-1">
          You can learn more about running evaluations in the playground in the{" "}
          <Button
            size="sm"
            variant="link"
            className="inline-flex h-auto px-0"
            asChild
          >
            <a
              href={buildDocsUrl(
                "/prompt_engineering/playground#running-experiments-in-the-playground",
              )}
              target="_blank"
              rel="noreferrer"
            >
              documentation guide.
            </a>
          </Button>
        </div>
        <div className="pt-2">
          You can get started today by navigating to the{" "}
          <SheetClose asChild>
            <Button
              size="sm"
              variant="link"
              className="inline-flex h-auto px-0"
              asChild
            >
              <Link to="/$workspaceName/playground" params={{ workspaceName }}>
                Playground.
              </Link>
            </Button>
          </SheetClose>{" "}
          From there, you can add prompts with variables in the format{" "}
          <span className="text-emerald-500">{`{{ variable_name }}`}</span> and
          evaluate them against your Opik datasets.
        </div>
      </div>
    </div>
  );
};

export default UsingPlaygroundTab;
