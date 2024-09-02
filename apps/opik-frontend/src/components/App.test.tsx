import { render } from "@testing-library/react";

import App from "./App";

describe("<App />", () => {
  it("should render the App", () => {
    const { container } = render(<App />);
    expect(container).toBeInTheDocument();
  });
});
