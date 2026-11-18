import httpx

from opik.integrations.sagemaker import auth

URL = "https://partner-app.example.com/api/v1/private/attachment/upload"


class FakeAuthProvider:
    def __init__(self) -> None:
        self.signed_bodies = []

    def get_signed_request(self, url, method, headers, body):
        self.signed_bodies.append(body)
        return url, {"Authorization": "fake-signature"}


def test_sagemaker_auth__streamed_body__body_signed_and_sent(monkeypatch, respx_mock):
    monkeypatch.setenv("AWS_PARTNER_APP_AUTH", "true")
    route = respx_mock.put(URL).respond(200)
    auth_provider = FakeAuthProvider()
    client = httpx.Client(auth=auth.SagemakerAuth(auth_provider))

    client.put(URL, content=iter([b"first-", b"second"]))

    assert auth_provider.signed_bodies == [b"first-second"]
    request = route.calls[0].request
    assert request.headers["Authorization"] == "fake-signature"
    assert request.content == b"first-second"


def test_sagemaker_auth__not_in_sagemaker__request_sent_once_unsigned(
    monkeypatch, respx_mock
):
    monkeypatch.delenv("AWS_PARTNER_APP_AUTH", raising=False)
    route = respx_mock.put(URL).respond(200)
    auth_provider = FakeAuthProvider()
    client = httpx.Client(auth=auth.SagemakerAuth(auth_provider))

    client.put(URL, content=b"data")

    assert route.call_count == 1
    assert auth_provider.signed_bodies == []
