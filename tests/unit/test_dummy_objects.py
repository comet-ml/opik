from comet_llm.dummy_api import dummy_class


def test_dummy_object_not_fail():
    dummy_class.DummyClass()
    dummy_class.DummyClass(1,2,3)
    dummy_class.DummyClass(1,2,3, b=4)

    with dummy_class.DummyClass() as dummy_object:
        dummy_object.a = 5
        dummy_object.a(1,2,3)
