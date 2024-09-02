import { createRoot } from "react-dom/client";
import "tailwindcss/tailwind.css";
import App from "@/components/App";
import usePluginsStore from "@/store/PluginsStore";

import "./main.scss";
// other styles
import "react18-json-view/src/style.css";

const container = document.getElementById("root") as HTMLDivElement;
const root = createRoot(container);

usePluginsStore.getState().setupPlugins(import.meta.env.MODE);
root.render(<App />);
