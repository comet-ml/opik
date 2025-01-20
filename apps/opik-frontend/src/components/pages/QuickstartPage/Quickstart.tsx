import React from "react";
import { useRouter } from "@tanstack/react-router";
import { MoveLeft } from "lucide-react";
import { Button } from "@/components/ui/button";
import FrameworkIntegrations from "@/components/pages-shared/onboarding/FrameworkIntegrations/FrameworkIntegrations";
import { QUICKSTART_INTEGRATIONS } from "@/components/pages-shared/onboarding/FrameworkIntegrations/quickstart-integrations";

type QuickstartProps = {
  apiKey?: string;
  showColabLinks?: boolean;
};

const Quickstart: React.FunctionComponent<QuickstartProps> = ({
  apiKey,
  showColabLinks = true,
}) => {
  const router = useRouter();

  return (
    <div className="flex w-full min-w-fit flex-col pb-12 pt-5">
      <div className="pb-10">
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
        <div className="pt-5">
          <h1 className="comet-title-xl text-center">Quickstart guide</h1>
          <div className="comet-body-s m-auto mt-4 w-[468px] self-center text-center text-muted-slate">
            Select the framework and follow the instructions to integrate Opik
            with your own code or use our ready-to-run examples on the right.
          </div>
        </div>
      </div>

      <FrameworkIntegrations
        integrationList={QUICKSTART_INTEGRATIONS}
        apiKey={apiKey}
        showColabLinks={showColabLinks}
      />
    </div>
  );
};

export default Quickstart;
