"""Tests purs du chemin JDBC direct et de la publication Bronze via staging."""

import ast
import pathlib
import uuid
from urllib.parse import quote_plus

import pandas as pd
from sqlalchemy import create_engine, inspect, text


_SRC = pathlib.Path(__file__).resolve().parent.parent / "moteur_universel.py"
_WANTED = {
    "build_source_connection",
    "_qualified_table",
    "_rename_table",
    "promote_staging_table",
    "drop_staging_table",
}


def _load_functions():
    tree = ast.parse(_SRC.read_text(encoding="utf-8"))
    functions = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in _WANTED]
    namespace = {
        "inspect": inspect,
        "text": text,
        "uuid": uuid,
        "quote_plus": quote_plus,
        "print": print,
    }
    exec(compile(ast.Module(body=functions, type_ignores=[]), "moteur_direct", "exec"), namespace)
    return namespace


_NS = _load_functions()
build_source_connection = _NS["build_source_connection"]
promote_staging_table = _NS["promote_staging_table"]


def test_build_source_connection_uses_resolved_metadata_without_csv():
    url, connect_args = build_source_connection("POSTGRES", "direct-jdbc://postgres", {
        "connect_timeout_seconds": 12,
        "source_connection": {
            "db_type": "POSTGRES",
            "host": "postgres-source",
            "port": "5432",
            "database": "hospital",
            "username": "etl user",
            "password": "p@ss/word",
        },
    })

    assert url == "postgresql+psycopg2://etl+user:p%40ss%2Fword@postgres-source:5432/hospital"
    assert connect_args["connect_timeout"] == 12


def test_staging_replace_and_append_are_published_only_when_complete(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'bronze.db'}")
    pd.DataFrame({"id": [1], "name": ["old"]}).to_sql("patients", engine, index=False, if_exists="replace")
    pd.DataFrame({"id": [2], "name": ["new"]}).to_sql("_iol_stage_replace", engine, index=False, if_exists="replace")

    promote_staging_table(engine, "SQLITE", None, "_iol_stage_replace", "patients", "replace")
    replaced = pd.read_sql("SELECT * FROM patients", engine)
    assert replaced.to_dict("records") == [{"id": 2, "name": "new"}]
    assert not inspect(engine).has_table("_iol_stage_replace")

    pd.DataFrame({"id": [3], "name": ["next"]}).to_sql("_iol_stage_append", engine, index=False, if_exists="replace")
    promote_staging_table(engine, "SQLITE", None, "_iol_stage_append", "patients", "append")
    appended = pd.read_sql("SELECT * FROM patients ORDER BY id", engine)
    assert appended.to_dict("records") == [
        {"id": 2, "name": "new"},
        {"id": 3, "name": "next"},
    ]
    assert not inspect(engine).has_table("_iol_stage_append")

    engine.dispose()


if __name__ == "__main__":
    test_build_source_connection_uses_resolved_metadata_without_csv()
    import tempfile
    with tempfile.TemporaryDirectory() as directory:
        test_staging_replace_and_append_are_published_only_when_complete(pathlib.Path(directory))
    print("ALL PASS")
