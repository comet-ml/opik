from playwright.sync_api import Page
import os


class BasePage:
    def __init__(self, page: Page, path: str, query_params: str = ""):
        """
        Base page class that handles workspace configuration for all pages.

        Args:
            page: Playwright page object
            path: The path part of the URL (e.g., 'projects', 'traces', etc.)
            query_params: Optional query parameters to append to the URL (e.g., '?tab=feedback-definitions')
        """
        self.page = page
        self.workspace = os.environ.get("OPIK_WORKSPACE", "default")
        self.base_url = os.environ.get("OPIK_BASE_URL", "http://localhost:5173")

        # Remove leading/trailing slashes and combine path components
        clean_path = path.strip("/")
        self.path = f"{self.workspace}/{clean_path}"
        if query_params:
            # Ensure query params start with '?' if provided
            if not query_params.startswith("?"):
                query_params = f"?{query_params}"
            self.path = f"{self.path}{query_params}"

    def go_to_page(self):
        """Navigate to the page URL"""
        # Combine base URL with path, ensuring no double slashes
        full_url = f"{self.base_url.rstrip('/')}/{self.path}"
        self.page.goto(full_url)
