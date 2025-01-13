export type FrameworkIntegrationComponentProps = {
  apiKey?: string;
};

export type FrameworkIntegration = {
  label: string;
  logo: string;
  colab: string;
  documentation: string;
  component: React.FC<FrameworkIntegrationComponentProps>;
};
