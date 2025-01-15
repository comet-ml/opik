import React from "react";
import { Button } from "@/components/ui/button";
import { Link } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import { QUICKSTART_INTEGRATIONS } from "@/components/pages-shared/onboarding/FrameworkIntegrations/quickstart-integrations";
import FrameworkIntegrations from "@/components/pages-shared/onboarding/FrameworkIntegrations/FrameworkIntegrations";

type GetStartedProps = {
  apiKey?: string;
  showColabLinks?: boolean;
};

const GetStarted: React.FunctionComponent<GetStartedProps> = ({
  apiKey,
  showColabLinks = true,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <div className="flex w-full min-w-fit flex-col pb-12 pt-10">
      <div>
        <div className="comet-body-s text-center">Get started with Opik</div>
        <h1 className="comet-title-xl mt-4 text-center">
          Log your first trace
        </h1>
        <div className="comet-body-s m-auto mt-4 w-[468px] self-center pb-10 text-center text-muted-slate">
          Select a framework and follow the instructions to integrate Comet with
          your code, or explore our ready-to-run examples on the right
        </div>
      </div>
      <FrameworkIntegrations
        integrationList={QUICKSTART_INTEGRATIONS}
        apiKey={apiKey}
        showColabLinks={showColabLinks}
      />
      <Button variant="link" className="mt-2" size="sm" asChild>
        <Link to="/$workspaceName/home" params={{ workspaceName }}>
          Skip, explore the platform on my own
        </Link>
      </Button>
    </div>
  );
};

export default GetStarted;
