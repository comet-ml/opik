from opik.configurator.mcp import env


def test_build_mcp_env__cloud__api_key_and_workspace_only():
    result = env.build_mcp_env(
        api_key="some-key",
        workspace="my-workspace",
        base_url="https://www.comet.com/",
        api_url="https://www.comet.com/opik/api/",
        use_local=False,
        self_hosted_comet=False,
    )
    assert result == {
        "OPIK_API_KEY": "some-key",
        "COMET_WORKSPACE": "my-workspace",
    }


def test_build_mcp_env__self_hosted_comet__url_override_is_base_url():
    result = env.build_mcp_env(
        api_key="some-key",
        workspace="my-workspace",
        base_url="https://opik.acme.com/",
        api_url="https://opik.acme.com/opik/api/",
        use_local=False,
        self_hosted_comet=True,
    )
    assert result == {
        "OPIK_API_KEY": "some-key",
        "COMET_WORKSPACE": "my-workspace",
        "COMET_URL_OVERRIDE": "https://opik.acme.com",
    }


def test_build_mcp_env__local__opik_url_is_api_url_without_api_key():
    result = env.build_mcp_env(
        api_key=None,
        workspace="default",
        base_url="http://localhost:5173/",
        api_url="http://localhost:5173/api/",
        use_local=True,
        self_hosted_comet=False,
    )
    assert result == {
        "COMET_WORKSPACE": "default",
        "OPIK_URL": "http://localhost:5173/api/",
    }


def test_build_mcp_env__workspace_passed_verbatim__not_lowercased():
    result = env.build_mcp_env(
        api_key="some-key",
        workspace="Mixed-Case-WS",
        base_url="https://www.comet.com/",
        api_url="https://www.comet.com/opik/api/",
        use_local=False,
        self_hosted_comet=False,
    )
    assert result["COMET_WORKSPACE"] == "Mixed-Case-WS"
