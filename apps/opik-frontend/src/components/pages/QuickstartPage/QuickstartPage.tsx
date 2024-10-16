import usePluginsStore from "@/store/PluginsStore";
import Quickstart from "@/components/pages/QuickstartPage/Quickstart";

const QuickstartPage = () => {
  const QuickstartPage = usePluginsStore((state) => state.QuickstartPage);

  if (QuickstartPage) {
    return <QuickstartPage />;
  }

  return <Quickstart />;
};

export default QuickstartPage;
