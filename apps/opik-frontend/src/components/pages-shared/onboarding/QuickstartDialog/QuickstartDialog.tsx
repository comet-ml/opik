import SideDialog from "@/components/shared/SideDialog/SideDialog";
import React from "react";
import FrameworkIntegrations from "../FrameworkIntegrations/FrameworkIntegrations";
import { SheetTitle } from "@/components/ui/sheet";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import EvaluationExamples from "../EvaluationExamples/EvaluationExamples";

type QuickstartDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
};
const QuickstartDialog: React.FC<QuickstartDialogProps> = ({
  open,
  setOpen,
}) => {
  return (
    <SideDialog open={open} setOpen={setOpen}>
      <div className="flex w-full min-w-fit flex-col pb-12">
        <div className="pb-8">
          <SheetTitle>Quickstart guide</SheetTitle>
          <div className="comet-body-s text-muted-slate m-auto mt-4 w-[468px] self-center text-center">
            Select the framework and follow the instructions to integrate Opik
            with your own code or use our ready-to-run examples on the right.
          </div>
        </div>

        <Tabs defaultValue="logLLM" className="flex flex-col items-center">
          <TabsList className="mb-8 w-auto self-center">
            <TabsTrigger className="w-[200px]" value="logLLM">
              Log LLM calls
            </TabsTrigger>
            <TabsTrigger className="w-[200px]" value="runEvaluations">
              Run evaluations
            </TabsTrigger>
          </TabsList>

          <TabsContent value="logLLM">
            <FrameworkIntegrations />
          </TabsContent>
          <TabsContent value="runEvaluations">
            <EvaluationExamples />
          </TabsContent>
        </Tabs>
      </div>
    </SideDialog>
  );
};

export default QuickstartDialog;
