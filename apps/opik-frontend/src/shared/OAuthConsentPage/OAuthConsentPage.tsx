import React, { useMemo, useState } from "react";
import useOAuthAuthorizeContext from "@/api/oauth/useOAuthAuthorizeContext";
import useOAuthConsentMutation from "@/api/oauth/useOAuthConsentMutation";
import { OAuthAuthorizeContext, OAuthWorkspaceInfo } from "@/api/oauth/types";
import { Button } from "@/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/ui/card";
import { Label } from "@/ui/label";
import { RadioGroup, RadioGroupItem } from "@/ui/radio-group";
import Loader from "@/shared/Loader/Loader";
import { useToast } from "@/ui/use-toast";
import {
  buildConsentRequest,
  denyAndRedirect,
  extractErrorMessage,
  parseParams,
} from "./helpers";

const OAuthConsentPage: React.FC = () => {
  const params = useMemo(() => parseParams(window.location.search), []);
  const { toast } = useToast();
  const [selectedWorkspaceId, setSelectedWorkspaceId] = useState<string | null>(
    null,
  );
  const [logoFailed, setLogoFailed] = useState(false);

  const contextQuery = useOAuthAuthorizeContext(
    {
      client_id: params?.client_id ?? "",
      redirect_uri: params?.redirect_uri ?? "",
    },
    { enabled: !!params },
  );

  const consent = useOAuthConsentMutation();

  if (!params) {
    return (
      <FullPage>
        <ConsentError
          title="Invalid authorization request"
          description="Required parameters are missing. Restart the authorization flow from your AI host."
        />
      </FullPage>
    );
  }

  if (contextQuery.isLoading) {
    return (
      <FullPage>
        <Loader />
      </FullPage>
    );
  }

  if (contextQuery.isError || !contextQuery.data) {
    return (
      <FullPage>
        <ConsentError
          title="Authorization unavailable"
          description="We couldn't load the authorization details. Your session may have expired or the client is not registered. Sign in again and retry from your AI host."
        />
      </FullPage>
    );
  }

  const data: OAuthAuthorizeContext = contextQuery.data;
  const workspaces = data.workspaces ?? [];
  const effectiveWorkspaceId = selectedWorkspaceId ?? workspaces[0]?.id ?? null;
  const canAllow =
    !consent.isPending && workspaces.length > 0 && !!effectiveWorkspaceId;

  const handleAllow = () => {
    const workspace = workspaces.find((w) => w.id === effectiveWorkspaceId);
    if (!workspace) {
      toast({
        title: "Select a workspace",
        description: "Choose which workspace to grant access to.",
        variant: "destructive",
      });
      return;
    }
    consent.mutate(buildConsentRequest(params, workspace, data.csrf_token), {
      onSuccess: ({ redirect_to }) => {
        window.location.href = redirect_to;
      },
      onError: (error) => {
        toast({
          title: "Could not complete authorization",
          description: extractErrorMessage(
            error,
            "The server rejected the consent. Please retry from your AI host.",
          ),
          variant: "destructive",
        });
      },
    });
  };

  const handleDeny = () => {
    if (!denyAndRedirect(params.redirect_uri, params.state)) {
      toast({
        title: "Could not complete authorization",
        description:
          "The redirect target is invalid. Restart the authorization flow from your AI host.",
        variant: "destructive",
      });
    }
  };

  return (
    <FullPage>
      <Card className="w-full max-w-md">
        <CardHeader className="space-y-3">
          {data.client_logo_uri && !logoFailed ? (
            <img
              src={data.client_logo_uri}
              alt={`${data.client_name} logo`}
              className="size-12 rounded"
              onError={() => setLogoFailed(true)}
            />
          ) : null}
          <CardTitle>{data.client_name}</CardTitle>
          <CardDescription>
            wants access to your Opik workspace. Approving grants the AI host
            full access to data in the selected workspace, with your existing
            permissions.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <WorkspacePicker
            workspaces={workspaces}
            value={effectiveWorkspaceId}
            onChange={setSelectedWorkspaceId}
          />
        </CardContent>
        <CardFooter className="flex justify-end gap-2">
          <Button
            variant="outline"
            onClick={handleDeny}
            disabled={consent.isPending}
          >
            Deny
          </Button>
          <Button onClick={handleAllow} disabled={!canAllow}>
            {consent.isPending ? "Approving…" : "Allow"}
          </Button>
        </CardFooter>
      </Card>
    </FullPage>
  );
};

const WorkspacePicker: React.FC<{
  workspaces: OAuthWorkspaceInfo[];
  value: string | null;
  onChange: (id: string) => void;
}> = ({ workspaces, value, onChange }) => {
  if (workspaces.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        No eligible workspaces. Create one in Opik before approving this
        request.
      </p>
    );
  }

  return (
    <div className="space-y-3">
      <Label>Workspace</Label>
      <RadioGroup value={value ?? undefined} onValueChange={onChange}>
        {workspaces.map((w) => (
          <div key={w.id} className="flex items-center space-x-2">
            <RadioGroupItem value={w.id} id={`ws-${w.id}`} />
            <Label htmlFor={`ws-${w.id}`} className="font-normal">
              {w.name}
            </Label>
          </div>
        ))}
      </RadioGroup>
    </div>
  );
};

const FullPage: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div className="flex min-h-screen items-center justify-center p-6">
    {children}
  </div>
);

const ConsentError: React.FC<{ title: string; description: string }> = ({
  title,
  description,
}) => (
  <Card className="w-full max-w-md">
    <CardHeader>
      <CardTitle>{title}</CardTitle>
      <CardDescription>{description}</CardDescription>
    </CardHeader>
  </Card>
);

export default OAuthConsentPage;
