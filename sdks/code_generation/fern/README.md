# How to generate new client code for communication with Opik backend

## Pre-requirements
For either the automatic script or the manual steps:
- You'll need to install the fern SDK: https://github.com/fern-api/fern

This is a one time process in the local machine generating the code.

## Automatic process
> [!IMPORTANT]
> You should generally run this script instead of manual steps below.
> The only pre-requirement is installing the fern SDK.

The following script automates this whole process:
```bash
./scripts/generate_openapi.sh
```

You simply need to run it from the repository base directory.

## Manual process

These are the manual steps (not recommended), as alternative to the automatic script.
In this case, it's also a pre-requirement to install the fern SDK.
Steps:
1. Run Opik locally using `./opik.sh`.
2. Go to http://localhost:3003/ (URL for backend API specification)
3. Download openapi specification file - `openapi.yaml`
4. Put this file into `code_generation/fern/openapi/openapi.yaml` (overwrite it).<br>
   Note that this path contains the version of the schema from which the code in `src/opik/rest_api` for the SDK was generated. Therefore, it might not be the latest version of the schema.
5. Run `fern generate` from inside `code_generation/fern` folder. This will generate a python code inside the directory called `sdks` near the `fern` one.
7. Replace content of `src/opik/rest_api` with the python package inside `sdks` (there will be few nested directories, navigate until you find python files)
8. Run `pre-commit run --all-files` to format code
