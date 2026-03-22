import { RouterProvider } from "@tanstack/react-router";
import { router } from "@/v2/router";

function App() {
  return <RouterProvider router={router} />;
}

export default App;
