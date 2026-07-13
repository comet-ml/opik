import React from "react";
import ReactDOM from "react-dom/client";

import "../styles/tokens.generated.css";
import "../main.scss";
import Preview from "./Preview";

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <Preview />
  </React.StrictMode>,
);
