from comet_llm import dummy_objects

def test_dummy_object_not_fail():
    dummy_objects.dummy_callable()
    dummy_objects.dummy_callable(1, 2, 3)
    dummy_objects.dummy_callable(1, 2, b=3)

    assert dummy_objects.dummy_callable() is None


    dummy_objects.DummyClass()
    dummy_objects.DummyClass(1,2,3)
    dummy_objects.DummyClass(1,2,3, b=4)

    with dummy_objects.DummyClass() as dummy_object:
        dummy_object.a = 5
        dummy_object.a(1,2,3)
