import React, { useMemo, useState } from "react";
import get from "lodash/get";
import useOAuthAuthorizeContext from "@/api/oauth/useOAuthAuthorizeContext";
import useOAuthConsentMutation from "@/api/oauth/useOAuthConsentMutation";
import { OAuthAuthorizeContext } from "@/api/oauth/types";
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

type ParsedParams = {
  client_id: string;
  redirect_uri: string;
  code_challenge: string;
  code_challenge_method: string;
  resource: string;
  state: string | null;
};

const parseParams = (search: string): ParsedParams | null => {
  const q = new URLSearchParams(search);
  // response_type is required by the OAuth spec; we gate on its presence but
  // never forward it (the backend already validated it before this redirect).
  const required = [
    "client_id",
    "redirect_uri",
    "response_type",
    "code_challenge",
    "code_challenge_method",
    "resource",
  ] as const;
  for (const k of required) {
    if (!q.get(k)) return null;
  }
  return {
    client_id: q.get("client_id") as string,
    redirect_uri: q.get("redirect_uri") as string,
    code_challenge: q.get("code_challenge") as string,
    code_challenge_method: q.get("code_challenge_method") as string,
    resource: q.get("resource") as string,
    state: q.get("state"),
  };
};

const denyAndRedirect = (
  redirectUri: string,
  state: string | null,
): boolean => {
  try {
    const url = new URL(redirectUri);
    url.searchParams.set("error", "access_denied");
    if (state) url.searchParams.set("state", state);
    window.location.href = url.toString();
    return true;
  } catch {
    return false;
  }
};

const OAuthConsentPage: React.FC = () => {
  const params = useMemo(() => parseParams(window.location.search), []);
  const { toast } = useToast();
  const [selectedWorkspaceId, setSelectedWorkspaceId] = useState<string | null>(
    null,
  );

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
    consent.mutate(
      {
        client_id: params.client_id,
        redirect_uri: params.redirect_uri,
        code_challenge: params.code_challenge,
        code_challenge_method: params.code_challenge_method,
        resource: params.resource,
        state: params.state,
        workspace_id: workspace.id,
        workspace_name: workspace.name,
        csrf: data.csrf_token,
      },
      {
        onSuccess: ({ redirect_to }) => {
          window.location.href = redirect_to;
        },
        onError: (error) => {
          const description = get(
            error,
            ["response", "data", "message"],
            "The server rejected the consent. Please retry from your AI host.",
          );
          toast({
            title: "Could not complete authorization",
            description,
            variant: "destructive",
          });
        },
      },
    );
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
          {data.client_logo_uri ? (
            <img
              src={data.client_logo_uri}
              alt={`${data.client_name} logo`}
              className="size-12 rounded"
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
          {workspaces.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              No eligible workspaces. Create one in Opik before approving this
              request.
            </p>
          ) : (
            <div className="space-y-3">
              <Label>Workspace</Label>
              <RadioGroup
                value={effectiveWorkspaceId ?? undefined}
                onValueChange={setSelectedWorkspaceId}
              >
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
          )}
        </CardContent>
        <CardFooter className="flex justify-end gap-2">
          <Button
            variant="outline"
            onClick={handleDeny}
            disabled={consent.isPending}
          >
            Deny
          </Button>
          <Button
            onClick={handleAllow}
            disabled={
              consent.isPending ||
              workspaces.length === 0 ||
              !effectiveWorkspaceId
            }
          >
            {consent.isPending ? "Approving…" : "Allow"}
          </Button>
        </CardFooter>
      </Card>
    </FullPage>
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
