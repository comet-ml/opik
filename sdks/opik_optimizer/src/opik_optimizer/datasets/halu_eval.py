import opik

def halu_eval_300(
    test_mode: bool = False
) -> opik.Dataset:
    """
    Dataset containing the first 300 samples of the HaluEval dataset.
    """
    dataset_name = "halu_eval_300" if not test_mode else "halu_eval_300_test"
    nb_items = 300 if not test_mode else 5

    client = opik.Opik()
    dataset = client.get_or_create_dataset(dataset_name)
    
    items = dataset.get_items()
    if len(items) == nb_items:
        return dataset
    elif len(items) != 0:
        raise ValueError(f"Dataset {dataset_name} contains {len(items)} items, expected {nb_items}. We recommend deleting the dataset and re-creating it.")
    elif len(items) == 0:
        import pandas as pd

        try:
            df = pd.read_parquet(
                "hf://datasets/pminervini/HaluEval/general/data-00000-of-00001.parquet"
            )
        except Exception:
            raise Exception("Unable to download HaluEval; please try again") from None

        sample_size = min(nb_items, len(df))
        df_sampled = df.sample(n=sample_size, random_state=42)

        dataset_records = [
            {
                "input": x["user_query"],
                "llm_output": x["chatgpt_response"],
                "expected_hallucination_label": x["hallucination"],
            }
            for x in df_sampled.to_dict(orient="records")
        ]

        dataset.insert(dataset_records)
        return dataset
