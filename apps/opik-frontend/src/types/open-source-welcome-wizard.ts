export interface OpenSourceWelcomeWizardTracking {
  workspace_id: string;
  completed: boolean;
  email?: string;
  role?: string;
  integrations?: string[];
  join_beta_program?: boolean;
  submitted_at?: string;
  created_at?: string;
  created_by?: string;
  last_updated_at?: string;
  last_updated_by?: string;
}

export interface OpenSourceWelcomeWizardSubmission {
  role?: string;
  integrations?: string[];
  email?: string;
  joinBetaProgram?: boolean;
}
