import pytest
import os
import random
import string
import re
from playwright.sync_api import Page
from tests.config import EnvConfig


def generate_suffix_no_consecutive(length=20):
    if length <= 0:
        return ""

    allowed_chars = string.ascii_lowercase + string.digits
    if len(allowed_chars) < 2 and length > 1:
        raise ValueError(
            "Cannot generate non-consecutive string of length > 1 with < 2 unique characters."
        )

    password_chars = [random.choice(allowed_chars)]

    while len(password_chars) < length:
        last_char = password_chars[-1]
        next_char = random.choice(allowed_chars)
        while next_char == last_char:
            next_char = random.choice(allowed_chars)
        password_chars.append(next_char)

    return "".join(password_chars)


@pytest.fixture
def temp_user_with_api_key(page: Page, browser_context, env_config: EnvConfig):
    """
    Creates a temporary user, yields its API key, then deletes the user.
    This fixture only runs when ADMIN_API_KEY is set in the environment.

    Returns:
        dict: Dictionary containing username, password, and api_key
    """
    admin_api_key = os.environ.get("ADMIN_API_KEY")
    admin_url = os.environ.get("ADMIN_URL")
    if not admin_api_key:
        pytest.skip("ADMIN_API_KEY not set, skipping test")

    # Generate random username and password
    random_suffix = generate_suffix_no_consecutive(length=20)
    username = f"temp-user-{random_suffix}"
    password = f"Password123_{random_suffix}"
    email = f"{username}@test.com"
    base_url = re.sub(r"/opik$", "", env_config.base_url)

    # Create user
    send_request_to_create_user(page, base_url, username, password, env_config)

    # Perform login with the new user to set up browser context
    login_page = browser_context.new_page()

    response = api_login_request(page, base_url, username, password, env_config)

    os.environ["OPIK_WORKSPACE"] = username

    # Extract API key from login response
    api_key = get_api_key(page, base_url, username, password, env_config)
    os.environ["OPIK_API_KEY"] = api_key

    # Update env_config with the API key
    env_config.api_key = api_key

    login_page.close()

    # Yield the username, password, and API key for the test to use
    yield {
        "username": username,
        "password": password,
        "email": email,
        "api_key": api_key,
    }

    # Delete the user using admin endpoint
    response = page.request.delete(
        f"{admin_url}delete-user?userName={username}",
        headers={"Authorization": admin_api_key},
        timeout=30000,
    )

    if response.status != 200 and response.status != 204:
        print(
            f"Warning: Failed to delete user {username}. Status: {response.status}, Response: {response.text()}"
        )


def send_request_to_create_user(page: Page, base_url, username, password, env_config):
    """Create a new user with the given username and password"""
    response = page.request.post(
        f"{base_url}/api/auth/new",
        data={
            "userName": f"{username}",
            "email": f"{username}@test.com",
            "plainTextPassword": password,
        },
    )

    if response.status != 200 and response.status != 201:
        raise Exception(
            f"Failed to create user. Status: {response.status}, Response: {response.text()}"
        )


def get_user_info(page, base_url, env_config):
    """Get user information including API keys"""
    response = page.request.get(f"{base_url}/api/auth/test")

    if response.status != 200:
        raise Exception(
            f"Failed to get user info. Status: {response.status}, Response: {response.text()}"
        )

    response_json = response.json()
    return response_json


def api_login_request(page, base_url, username, password, env_config):
    """Login to generate an API key if none exists"""
    response = page.request.post(
        f"{base_url}/api/auth/login",
        data={"email": f"{username}@test.com", "plainTextPassword": password},
    )

    if response.status != 200:
        raise Exception(
            f"Login failed. Status: {response.status}, Response: {response.text()}"
        )

    return response.json()


def get_api_key(page, base_url, username, password, env_config):
    """Get the user's API key"""
    api_keys = get_user_info(page, base_url, env_config)["apiKeys"]
    if not api_keys:
        login_response = api_login_request(page, username, password, env_config)
        if "apiKeys" in login_response and login_response["apiKeys"]:
            return login_response["apiKeys"][0]
        api_keys = get_user_info(page, env_config)["apiKeys"]
        if not api_keys:
            raise Exception("Failed to get API key")
    return api_keys[0]
