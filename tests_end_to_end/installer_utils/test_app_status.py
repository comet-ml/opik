from playwright.sync_api import Page, expect


def test_app_loads(page: Page):
    page.goto("http://localhost:5173/default/projects")
    expect(page.get_by_role("heading", name="Projects")).to_be_visible()
