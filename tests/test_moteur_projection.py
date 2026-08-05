"""
Test (d) — projection `fields` et règle de sûreté incrémentale de moteur_universel.py.

Le moteur exécute du code au niveau module (lecture de sys.argv) : on n'importe donc
PAS le module. On extrait par AST uniquement les fonctions pures à tester et on les
exécute dans un namespace isolé — on teste ainsi le VRAI code sans effets de bord.

Lancement :
  python tests/test_moteur_projection.py
  (ou via pytest : pytest tests/test_moteur_projection.py)
"""
import ast
import json
import pathlib

import pandas as pd

_SRC = pathlib.Path(__file__).resolve().parent.parent / "moteur_universel.py"
_WANTED = {"normalize_fields", "apply_projection", "safe_watermark", "safe_identifier"}


def _load_pure_functions():
    tree = ast.parse(_SRC.read_text(encoding="utf-8"))
    funcs = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in _WANTED]
    ns = {"json": json, "re": __import__("re")}
    exec(compile(ast.Module(body=funcs, type_ignores=[]), "moteur_helpers", "exec"), ns)
    return ns


_NS = _load_pure_functions()
normalize_fields = _NS["normalize_fields"]
apply_projection = _NS["apply_projection"]
safe_watermark = _NS["safe_watermark"]


def test_normalize_fields_variants():
    assert normalize_fields(None) is None
    assert normalize_fields([]) is None
    assert normalize_fields("[]") is None
    assert normalize_fields(["a", "b"]) == ["a", "b"]
    assert normalize_fields('["date_op","amount"]') == ["date_op", "amount"]
    assert normalize_fields("date_op, amount ,tx_id") == ["date_op", "amount", "tx_id"]


def test_projection_keeps_only_requested_columns():
    df = pd.DataFrame({"date_op": [1], "amount": [10], "junk": ["x"]})
    out = apply_projection(df, ["date_op", "amount"], None)
    assert list(out.columns) == ["date_op", "amount"]


def test_projection_force_includes_incremental_column():
    df = pd.DataFrame({"date_op": [1, 2], "amount": [10, 20], "junk": ["x", "y"]})
    # fields ne contient PAS la colonne incrémentale -> elle doit être ajoutée d'office
    out = apply_projection(df, ["amount"], "date_op")
    assert "date_op" in out.columns, "incremental_column doit toujours être extraite"
    assert "amount" in out.columns
    assert "junk" not in out.columns


def test_projection_missing_columns_are_ignored_gracefully():
    df = pd.DataFrame({"date_op": [1], "amount": [10]})
    out = apply_projection(df, ["amount", "does_not_exist"], None)
    assert list(out.columns) == ["amount"]


def test_projection_none_keeps_all():
    df = pd.DataFrame({"a": [1], "b": [2]})
    out = apply_projection(df, None, None)
    assert list(out.columns) == ["a", "b"]


def test_watermark_max_is_safe():
    df = pd.DataFrame({"date_op": pd.to_datetime(["2026-06-10", "2026-06-15", "2026-06-12"])})
    raw_max = df["date_op"].max()
    wm = safe_watermark(raw_max)
    assert wm.startswith("2026-06-15")


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print(f"[PASS] {name}")
            except Exception as e:  # noqa: BLE001
                failures += 1
                print(f"[FAIL] {name}: {e!r}")
    print(f"\n{'ALL PASS' if failures == 0 else str(failures) + ' FAILURE(S)'}")
    raise SystemExit(1 if failures else 0)
