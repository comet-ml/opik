import usePluginsStore from "@/store/PluginsStore";
import FrameworkIntegrationsContent, {
  FrameworkIntegrationsContentProps,
} from "./FrameworkIntegrationsContent";

export type FrameworkIntegrationsProps = Omit<
  FrameworkIntegrationsContentProps,
  "apiKey"
>;
const FrameworkIntegrations: React.FC<FrameworkIntegrationsProps> = (props) => {
  const FrameworkIntegrations = usePluginsStore(
    (state) => state.FrameworkIntegrations,
  );

  if (FrameworkIntegrations) {
    return <FrameworkIntegrations {...props} />;
  }

  return <FrameworkIntegrationsContent {...props} />;
};

export default FrameworkIntegrations;
