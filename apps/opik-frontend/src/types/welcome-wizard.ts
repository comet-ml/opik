export interface WelcomeWizardTracking {
  completed: boolean;
}

export interface WelcomeWizardSubmission {
  role?: string;
  integrations?: string[];
  email?: string;
  joinBetaProgram?: boolean;
}
