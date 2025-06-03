import opik

TINY_TEST_ITEMS = [
        {
            "text": "What is the capital of France?",
            "label": "Paris",
            "metadata": {
                "context": "France is a country in Europe. Its capital is Paris."
            },
        },
        {
            "text": "Who wrote Romeo and Juliet?",
            "label": "William Shakespeare",
            "metadata": {
                "context": "Romeo and Juliet is a famous play written by William Shakespeare."
            },
        },
        {
            "text": "What is 2 + 2?",
            "label": "4",
            "metadata": {"context": "Basic arithmetic: 2 + 2 equals 4."},
        },
        {
            "text": "What is the largest planet in our solar system?",
            "label": "Jupiter",
            "metadata": {
                "context": "Jupiter is the largest planet in our solar system."
            },
        },
        {
            "text": "Who painted the Mona Lisa?",
            "label": "Leonardo da Vinci",
            "metadata": {"context": "The Mona Lisa was painted by Leonardo da Vinci."},
        },
    ]

def tiny_test(
    test_mode: bool = False
) -> opik.Dataset:
    """
    Dataset containing the first 5 samples of the HotpotQA dataset.
    """
    dataset_name = "tiny_test" if not test_mode else "tiny_test_test"
    nb_items = len(TINY_TEST_ITEMS)

    client = opik.Opik()
    dataset = client.get_or_create_dataset(dataset_name)
    
    items = dataset.get_items()
    if len(items) == nb_items:
        return dataset
    elif len(items) != 0:
        raise ValueError(f"Dataset {dataset_name} contains {len(items)} items, expected {nb_items}. We recommend deleting the dataset and re-creating it.")
    elif len(items) == 0:
        dataset.insert(TINY_TEST_ITEMS)
        return dataset

