import usePluginsStore from "@/store/PluginsStore";
import GetStarted from "@/components/pages/GetStartedPage/GetStarted";

const GetStartedPage = () => {
  const GetStartedPage = usePluginsStore((state) => state.GetStartedPage);

  if (GetStartedPage) {
    return <GetStartedPage />;
  }

  return <GetStarted />;
};
export default GetStartedPage;
