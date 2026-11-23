import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { FramePage } from "./FramePage";
import "opik-genui/styles.css";
import "./styles.css";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    {window.location.hash === "#frame" ? <FramePage /> : <App />}
  </StrictMode>,
);
