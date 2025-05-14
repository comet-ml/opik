import os
import pytest
from opik import opik_context, track, Attachment
from Traces.traces_config import PREFIX
from sdk_helpers import wait_for_number_of_traces_to_be_visible

attachments = [
    {"path": "test_files/attachments/audio01.wav", "name": "audio01.wav"},
    {"path": "test_files/attachments/audio02.mp3", "name": "audio02.mp3"},
    {"path": "test_files/attachments/json01.json", "name": "json01.json"},
    {"path": "test_files/attachments/pdf01.pdf", "name": "pdf01.pdf"},
    {"path": "test_files/attachments/test-image1.jpg", "name": "test-image1.jpg"},
    {"path": "test_files/attachments/test-image2.png", "name": "test-image2.png"},
    {"path": "test_files/attachments/test-image3.gif", "name": "test-image3.gif"},
    {"path": "test_files/attachments/test-image4.svg", "name": "test-image4.svg"},
    {"path": "test_files/attachments/text01.txt", "name": "text01.txt"},
    {"path": "test_files/attachments/video01.webm", "name": "video01.webm"},
]


@pytest.fixture(scope="function")
def log_x_traces_with_one_span_via_decorator(traces_number):
    @track(project_name=os.environ["OPIK_PROJECT_NAME"])
    def f2(input: str):
        return "test output"

    @track(project_name=os.environ["OPIK_PROJECT_NAME"])
    def f1(input: str):
        opik_context.update_current_trace(name=PREFIX + str(i))
        return f2(input)

    for i in range(traces_number):
        f1("test input")

    yield


@pytest.fixture(scope="function")
def log_x_traces_with_one_span_via_client(client, traces_number):
    for i in range(traces_number):
        client_trace = client.trace(
            name=PREFIX + str(i),
            project_name=os.environ["OPIK_PROJECT_NAME"],
            input={"input": "test input"},
            output={"output": "test output"},
        )
        _ = client_trace.span(
            name="span", input={"input": "test input"}, output={"output": "test output"}
        )
    wait_for_number_of_traces_to_be_visible(
        project_name=os.environ["OPIK_PROJECT_NAME"], number_of_traces=traces_number
    )
    yield


@pytest.fixture(scope="function")
def create_traces(request, traces_number):
    _ = request.getfixturevalue(request.param)
    yield 0


@pytest.fixture(scope="function", params=attachments)
def log_trace_attachment_low_level(client, request):
    client.trace(
        name="trace_with_attachement",
        input={"instruction": "Analyze the pdf document, ..."},
        project_name=os.environ["OPIK_PROJECT_NAME"],
        attachments=[Attachment(data=request.param["path"])],
    )
    return request.param["name"]


@pytest.fixture(scope="function", params=attachments)
def log_trace_attachment_decorator(request):
    @track(project_name=os.environ["OPIK_PROJECT_NAME"])
    def log_attachment():
        opik_context.update_current_trace(
            attachments=[Attachment(data=request.param["path"])]
        )

    log_attachment()
    return request.param["name"]


@pytest.fixture(scope="function", params=attachments)
def log_trace_attachment_in_span(client, request):
    trace = client.trace(
        name="my_trace",
        project_name=os.environ["OPIK_PROJECT_NAME"],
        input={"user_question": "Hello, how are you?"},
        output={"response": "Comment ça va?"},
    )

    span_name = "Add prompt template"

    trace.span(
        name=span_name,
        input={
            "text": "Hello, how are you?",
            "prompt_template": "Translate the following text to French: {text}",
        },
        output={"text": "Translate the following text to French: hello, how are you?"},
        attachments=[Attachment(data=request.param["path"])],
    )

    return request.param["name"], span_name
