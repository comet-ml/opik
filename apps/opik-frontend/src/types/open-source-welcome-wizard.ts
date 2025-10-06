export interface OpenSourceWelcomeWizardTracking {
  completed: boolean;
}

export interface OpenSourceWelcomeWizardSubmission {
  role?: string;
  integrations?: string[];
  email?: string;
  joinBetaProgram?: boolean;
}
