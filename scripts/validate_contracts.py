from pathlib import Path
import json
import sys

from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parent.parent
CONTRACTS = ROOT / "contracts"


def load(path: Path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def main() -> int:
    schema_path = CONTRACTS / "data-sharing-contract.schema.json"
    schema = load(schema_path)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    failures = 0

    for example in sorted((CONTRACTS / "examples").glob("*.contract.json")):
        errors = sorted(validator.iter_errors(load(example)), key=lambda item: list(item.path))
        if not errors:
            print(f"OK {example.relative_to(ROOT)}")
            continue
        failures += 1
        for error in errors:
            location = ".".join(str(part) for part in error.path) or "$"
            print(f"ERROR {example.relative_to(ROOT)} {location}: {error.message}", file=sys.stderr)

    for path in sorted(CONTRACTS.glob("*.schema.json")):
        schema_document = load(path)
        Draft202012Validator.check_schema(schema_document)

        # Un schema dont les propres exemples ne valident plus signale une derive
        # entre le contrat et ce que les services echangent reellement.
        own_validator = Draft202012Validator(schema_document, format_checker=FormatChecker())
        for index, example in enumerate(schema_document.get("examples", [])):
            errors = sorted(own_validator.iter_errors(example), key=lambda item: list(item.path))
            if not errors:
                continue
            failures += 1
            for error in errors:
                location = ".".join(str(part) for part in error.path) or "$"
                print(
                    f"ERROR {path.relative_to(ROOT)} examples[{index}] {location}: {error.message}",
                    file=sys.stderr,
                )
        print(f"SCHEMA OK {path.relative_to(ROOT)}")

    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
