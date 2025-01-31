import { Button } from "@/components/ui/button";
import { SheetClose } from "@/components/ui/sheet";
import useAppStore from "@/store/AppStore";
import { Link } from "@tanstack/react-router";

const UsingPlaygroundTab = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <div className="flex flex-col gap-6 rounded-md border bg-white p-6">
      <div className="comet-body-s">
        To use the Playground, you will need to navigate to the{" "}
        <SheetClose asChild>
          <Button
            size="sm"
            variant="link"
            className="inline-flex h-auto px-0"
            asChild
          >
            <Link to="/$workspaceName/playground" params={{ workspaceName }}>
              Playground
            </Link>
          </Button>
        </SheetClose>{" "}
        page and:
        <div className="pt-1">
          1. Configure the LLM provider you want to use
        </div>
        <div className="pt-1">
          2. Enter the prompts you want to evaluate - You should include
          variables in the prompts using the{" "}
          <span className="text-emerald-500">{"{{ variable }}"}</span> syntax
        </div>
        <div className="pt-1">
          3. Select the dataset you want to evaluate on
        </div>
        <div className="pt-1">
          4. Click on the <span className="text-emerald-500">Evaluate</span>{" "}
          button
        </div>
      </div>
    </div>
  );
};

export default UsingPlaygroundTab;
