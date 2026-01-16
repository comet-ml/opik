import { isOnPremise, isProduction } from "@/plugins/comet/utils";
import initReoSnippet from "./snippet";

export type ReoIdentityType =
  | "email"
  | "github"
  | "linkedin"
  | "gmail"
  | "userID";

export interface ReoIdentity {
  username: string;
  type: ReoIdentityType;
  other_identities?: Array<{
    username: string;
    type: ReoIdentityType;
  }>;
  firstname?: string;
  lastname?: string;
  company?: string;
}

declare global {
  interface Window {
    Reo?: {
      init: (config: { clientID: string }) => void;
      identify: (identity: ReoIdentity) => void;
    };
  }
}

export const initReo = (clientId?: string) => {
  if (clientId && isProduction() && !isOnPremise()) {
    initReoSnippet(clientId);
  }
};

export const identifyReoUser = (identity: ReoIdentity) => {
  if (window.Reo && isProduction() && !isOnPremise()) {
    window.Reo.identify(identity);
  }
};
