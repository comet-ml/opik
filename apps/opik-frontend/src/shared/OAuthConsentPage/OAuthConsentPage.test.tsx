import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { OAuthAuthorizeContext, OAuthWorkspaceInfo } from "@/api/oauth/types";
import useOAuthAuthorizeContext from "@/api/oauth/useOAuthAuthorizeContext";
import useOAuthConsentMutation from "@/api/oauth/useOAuthConsentMutation";
import OAuthConsentPage from "./OAuthConsentPage";

vi.mock("@/api/oauth/useOAuthAuthorizeContext", () => ({
  default: vi.fn(),
}));
vi.mock("@/api/oauth/useOAuthConsentMutation", () => ({
  default: vi.fn(),
}));

const mutate = vi.fn();

const workspace = (name: string, is_default: boolean): OAuthWorkspaceInfo => ({
  id: `id-${name}`,
  name,
  is_default,
});

const renderPage = (workspaces: OAuthWorkspaceInfo[]) => {
  const data: OAuthAuthorizeContext = {
    client_name: "Test Client",
    workspaces,
    csrf_token: "csrf-1",
  };
  vi.mocked(useOAuthAuthorizeContext).mockReturnValue({
    data,
    isLoading: false,
    isError: false,
  } as ReturnType<typeof useOAuthAuthorizeContext>);

  return render(<OAuthConsentPage />);
};

describe("OAuthConsentPage workspace preselection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // The page reads the authorize params off the URL; without all of them it
    // renders the terminal "invalid request" card instead of the picker.
    window.history.replaceState(
      {},
      "",
      "/oauth/consent?client_id=c1&redirect_uri=http%3A%2F%2Flocalhost%3A1234%2Fcb" +
        "&response_type=code&code_challenge=chal&code_challenge_method=S256" +
        "&resource=https%3A%2F%2Fapi.example&state=xyz",
    );
    vi.mocked(useOAuthConsentMutation).mockReturnValue({
      mutate,
      isPending: false,
    } as unknown as ReturnType<typeof useOAuthConsentMutation>);
  });

  it("preselects the default workspace rather than the first one", () => {
    renderPage([
      workspace("staging", false),
      workspace("production", true),
      workspace("sandbox", false),
    ]);

    expect(screen.getByRole("radio", { name: "production" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "staging" })).not.toBeChecked();
  });

  it("falls back to the first workspace when none is flagged default", () => {
    renderPage([workspace("staging", false), workspace("production", false)]);

    expect(screen.getByRole("radio", { name: "staging" })).toBeChecked();
  });

  it("submits the preselected default workspace without any interaction", () => {
    renderPage([workspace("staging", false), workspace("production", true)]);

    fireEvent.click(screen.getByRole("button", { name: "Allow" }));

    expect(mutate).toHaveBeenCalledWith(
      expect.objectContaining({
        workspace_id: "id-production",
        workspace_name: "production",
      }),
      expect.anything(),
    );
  });

  it("lets an explicit pick override the default preselection", () => {
    renderPage([workspace("staging", false), workspace("production", true)]);

    fireEvent.click(screen.getByRole("radio", { name: "staging" }));

    expect(screen.getByRole("radio", { name: "staging" })).toBeChecked();

    fireEvent.click(screen.getByRole("button", { name: "Allow" }));

    expect(mutate).toHaveBeenCalledWith(
      expect.objectContaining({
        workspace_id: "id-staging",
        workspace_name: "staging",
      }),
      expect.anything(),
    );
  });
});
