import opik


def tiny_test(test_mode: bool = False) -> opik.Dataset:
    """
    Tiny QA benchmark (core_en subset from vincentkoc/tiny_qa_benchmark_pp).
    """
    dataset_name = "tiny_test_train" if not test_mode else "tiny_test_sample"
    nb_items = 5  # keep tiny dataset size consistent with tests/docs

    client = opik.Opik()
    dataset = client.get_or_create_dataset(dataset_name)

    items = dataset.get_items()
    if len(items) == nb_items:
        return dataset
    elif len(items) != 0:
        raise ValueError(
            f"Dataset {dataset_name} contains {len(items)} items, expected {nb_items}. We recommend deleting the dataset and re-creating it."
        )
    elif len(items) == 0:
        import datasets as ds

        download_config = ds.DownloadConfig(download_desc=False, disable_tqdm=True)
        ds.disable_progress_bar()
        try:
            # Load only the core_en subset JSONL from the repo
            # Use the generic JSON loader with streaming for efficiency
            hf_dataset = ds.load_dataset(
                "json",
                data_files="hf://datasets/vincentkoc/tiny_qa_benchmark_pp/data/core_en/core_en.jsonl",
                streaming=True,
                download_config=download_config,
            )["train"]

            data = []
            for i, item in enumerate(hf_dataset):
                if i >= nb_items:
                    break
                data.append(
                    {
                        "text": item.get("text", ""),
                        "label": item.get("label", ""),
                        # Preserve original tiny_test shape with metadata.context
                        "metadata": {"context": item.get("context", "")},
                    }
                )

            dataset.insert(data)
            return dataset
        finally:
            ds.enable_progress_bar()
