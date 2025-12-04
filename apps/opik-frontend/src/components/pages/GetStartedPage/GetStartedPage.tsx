import usePluginsStore from "@/store/PluginsStore";
import NewQuickstart from "@/components/pages/GetStartedPage/NewQuickstart";

const GetStartedPage = () => {
  const GetStartedPage = usePluginsStore((state) => state.GetStartedPage);

  if (GetStartedPage) {
    return <GetStartedPage />;
  }

  return <NewQuickstart />;
};
export default GetStartedPage;
