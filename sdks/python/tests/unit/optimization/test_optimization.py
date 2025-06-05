from unittest.mock import patch
import base64
import io

from opik.url_helpers import get_optimization_run_url_by_id
from opik.evaluation.report import display_optimization_run_link

from ...testlib import assert_equal


def test_get_optimization_run_url_by_id():
    URL_OVERRIDE = "https://URL/opik/api"
    ENCODED_URL = base64.b64encode(URL_OVERRIDE.encode("utf-8")).decode("utf-8")
    OPTIMIZATION_ID = "OPTIMIZATION-ID"
    DATASET_ID = "DATASET-ID"

    url = get_optimization_run_url_by_id(
        dataset_id=DATASET_ID,
        optimization_id=OPTIMIZATION_ID,
        url_override=URL_OVERRIDE,
    )

    assert_equal(
        url,
        f"{URL_OVERRIDE}/v1/session/redirect/optimizations/?optimization_id={OPTIMIZATION_ID}&dataset_id={DATASET_ID}&path={ENCODED_URL}",
    )


@patch("sys.stdout", new_callable=io.StringIO)
def test_display_optimization_run_link(mock_stdout):
    URL_OVERRIDE = "https://URL/opik/api"
    OPTIMIZATION_ID = "OPTIMIZATION-ID"
    DATASET_ID = "DATASET-ID"

    display_optimization_run_link(
        dataset_id=DATASET_ID,
        optimization_id=OPTIMIZATION_ID,
        url_override=URL_OVERRIDE,
    )

    output = mock_stdout.getvalue().strip()

    assert_equal(output, "View the optimization run in your Opik dashboard.")
