import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import ThreadFilters from "../../components/threads/ThreadFilters";

test("toggles hasComment filter", async () => {
  const setFilters = jest.fn();
  render(<ThreadFilters filters={{}} setFilters={setFilters} />);
  fireEvent.click(screen.getByLabelText(/Only show threads with comments/));
  await waitFor(() => {
    expect(setFilters).toHaveBeenCalledWith({ hasComment: true });
  });
});