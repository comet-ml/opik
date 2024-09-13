import useAppStore from "@/store/AppStore";
import usePluginsStore from "@/store/PluginsStore";
import { Link, Outlet } from "@tanstack/react-router";
import imageLogoUrl from "/images/logo_and_text.png";

const PageLayout = () => {
  const UserMenu = usePluginsStore((state) => state.UserMenu);
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <section className="relative flex h-screen min-h-0 w-screen min-w-0 flex-col overflow-hidden">
      <main>
        <nav className="comet-header-height flex w-full items-center justify-between gap-6 border-b pl-4 pr-6">
          <div className="flex-1">
            <Link to="/$workspaceName/projects" params={{ workspaceName }}>
              <img
                className="h-8 w-[26px] object-cover object-left"
                src={imageLogoUrl}
                alt="comet logo"
              />
            </Link>
          </div>

          {UserMenu ? <UserMenu /> : null}
        </nav>

        <section className="comet-header-inset absolute inset-x-0 bottom-0 overflow-auto bg-[#FCFCFD] px-6">
          <Outlet />
        </section>
      </main>
    </section>
  );
};

export default PageLayout;
