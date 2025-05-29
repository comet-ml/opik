import opik

def truthful_qa(
    test_mode: bool = False
) -> opik.Dataset:
    """
    Dataset containing the first 300 samples of the TruthfulQA dataset.
    """
    dataset_name = "truthful_qa" if not test_mode else "truthful_qa_test"
    nb_items = 300 if not test_mode else 5

    client = opik.Opik()
    dataset = client.get_or_create_dataset(dataset_name)
    
    items = dataset.get_items()
    if len(items) == nb_items:
        return dataset
    elif len(items) != 0:
        raise ValueError(f"Dataset {dataset_name} contains {len(items)} items, expected {nb_items}. We recommend deleting the dataset and re-creating it.")
    elif len(items) == 0:
        import datasets as ds

        # Load data from file and insert into the dataset
        download_config = ds.DownloadConfig(download_desc=False, disable_tqdm=True)
        ds.disable_progress_bar()
        
        gen_dataset = ds.load_dataset("truthful_qa", "generation", download_config=download_config)
        mc_dataset = ds.load_dataset("truthful_qa", "multiple_choice", download_config=download_config)
        
        data = []
        for gen_item, mc_item in zip(
            gen_dataset["validation"], mc_dataset["validation"]
        ):
            if len(data) >= nb_items:
                break
                
            # Get correct answers from both configurations
            correct_answers = set(gen_item["correct_answers"])
            if "mc1_targets" in mc_item:
                correct_answers.update(
                    [
                        choice
                        for choice, label in zip(
                            mc_item["mc1_targets"]["choices"],
                            mc_item["mc1_targets"]["labels"],
                        )
                        if label == 1
                    ]
                )
            if "mc2_targets" in mc_item:
                correct_answers.update(
                    [
                        choice
                        for choice, label in zip(
                            mc_item["mc2_targets"]["choices"],
                            mc_item["mc2_targets"]["labels"],
                        )
                        if label == 1
                    ]
                )

            # Get all possible answers
            all_answers = set(
                gen_item["correct_answers"] + gen_item["incorrect_answers"]
            )
            if "mc1_targets" in mc_item:
                all_answers.update(mc_item["mc1_targets"]["choices"])
            if "mc2_targets" in mc_item:
                all_answers.update(mc_item["mc2_targets"]["choices"])

            # Create a single example with all necessary fields
            example = {
                "question": gen_item["question"],
                "answer": gen_item["best_answer"],
                "choices": list(all_answers),
                "correct_answer": gen_item["best_answer"],
                "input": gen_item["question"],  # For AnswerRelevance metric
                "output": gen_item["best_answer"],  # For output_key requirement
                "context": gen_item.get("source", ""),  # Use source as context
                "type": "TEXT",  # Set type to TEXT as required by Opik
                "category": gen_item["category"],
                "source": "MANUAL",  # Set source to MANUAL as required by Opik
                "correct_answers": list(
                    correct_answers
                ),  # Keep track of all correct answers
                "incorrect_answers": gen_item[
                    "incorrect_answers"
                ],  # Keep track of incorrect answers
            }

            # Ensure all required fields are present
            required_fields = [
                "question",
                "answer",
                "choices",
                "correct_answer",
                "input",
                "output",
                "context",
            ]
            if all(field in example and example[field] for field in required_fields):
                data.append(example)
        ds.enable_progress_bar()
        
        dataset.insert(data)

        return dataset
