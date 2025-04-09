"""
Create an Opik dataset named hotpot-300 for experimentation.
"""
import opik
import os

HERE = os.path.abspath(os.path.dirname(__file__))


def make_hotpot_qa(size=300, seed=2024):
    from dspy.datasets import HotPotQA

    trainset = [x.with_inputs('question') for x in HotPotQA(train_seed=seed, train_size=size).train]

    data = []
    for row in trainset:
        d = row.toDict()
        del d["dspy_uuid"]
        del d["dspy_split"]
        data.append(d)

    opik_client = opik.Opik()
    dataset = opik_client.get_or_create_dataset(name=("hotpot-%s" % size))
    dataset.insert(data)


if __name__ == "__main__":
    make_hotpot_qa()
    print("Done")
