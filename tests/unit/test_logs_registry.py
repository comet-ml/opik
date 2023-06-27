from comet_llm import logs_registry


def test_happyflow():
    tested = logs_registry.LogsRegistry()

    tested.register_log("project-url-1")
    tested.register_log("project-url-2")
    tested.register_log("project-url-1")

    tested.as_dict() == {
        "project-url-1": 2,
        "project-url-2": 1
    }


def test_empty():
    tested = logs_registry.LogsRegistry()

    assert tested.empty()

    tested.register_log("project-url-1")

    assert not tested.empty()