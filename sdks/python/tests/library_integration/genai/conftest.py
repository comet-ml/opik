import os
import pytest
from ...testlib import patch_environ


@pytest.fixture(autouse=True)
def setup_genai_credentials():
    try:
        gcp_credentials = os.environ["GCP_CREDENTIALS_JSON"]
        with open("gcp_credentials.json", mode="wt") as output_file:
            output_file.write(gcp_credentials)

        os.environ["GOOGLE_APPLICATION_CREDENTIALS"] = "gcp_credentials.json"

        with patch_environ(
            add_keys={"GOOGLE_APPLICATION_CREDENTIALS": "gcp_credentials.json"}
        ):
            yield
    finally:
        try:
            os.remove("gcp_credentials.json")
        except OSError:
            pass
