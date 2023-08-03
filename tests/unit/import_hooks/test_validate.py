from comet_llm.import_hooks import validate

def test_args_kwargs__happyflow():
    args_kwargs = ([1], {"foo": "bar"})
    assert validate.args_kwargs(args_kwargs) is True


def test_args_kwargs__input_is_None__return_False():
    assert validate.args_kwargs(None) is False


def test_args_kwargs__input_is_not_tuple_or_list_of_length_2__return_False():
    assert validate.args_kwargs(42) is False


def test_args_kwargs__args_cant_be_parsed__return_False():
    args_kwargs = (42, {})
    assert validate.args_kwargs(args_kwargs) is False


def test_args_kwargs__kwargs_cant_be_parsed__return_False():
    args_kwargs = ([1], 42)
    assert validate.args_kwargs(args_kwargs) is False