from comet_llm import logs_registry

def test_happyflow():
    tested = logs_registry.LogsRegistry()

    tested.register_log("project-url-1")
    tested.register_log("project-url-2")
    tested.register_log("project-url-1")

    tested.summary() == {
        "project-url-1": 2,
        "project-url-2": 1
    }
