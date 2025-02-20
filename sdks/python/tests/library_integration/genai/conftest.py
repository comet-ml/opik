import os
import pytest
import json
from ...testlib import patch_environ


@pytest.fixture(autouse=True)
def setup_genai_credentials():
    try:
        gcp_credentials = os.environ["GCP_CREDENTIALS_JSON"]
        print("JSON str starts with ", gcp_credentials[:5])
        gcp_credentials_dict = json.loads(gcp_credentials)

        with open("gcp_credentials.json", mode="wt") as output_file:
            json.dump(gcp_credentials_dict, output_file)

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
