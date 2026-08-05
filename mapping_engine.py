import re


def _value(mapping, *keys, default=None):
    for key in keys:
        if key in mapping and mapping[key] is not None:
            return mapping[key]
    return default


def _source_fields(mapping):
    fields = _value(mapping, "sourceFields", "source_fields", default=[])
    if isinstance(fields, str):
        fields = [fields]
    return [str(field) for field in fields or [] if str(field).strip()]


def apply_workflow_mappings(df, mappings, already_pivot=False):
    """Apply declarative workflow mappings without evaluating arbitrary code."""
    if df is None or already_pivot or not mappings:
        return df

    result = df.copy()
    for mapping in mappings:
        mapping_type = str(_value(mapping, "mappingType", "mapping_type", default="DIRECT")).upper()
        target = str(_value(mapping, "iolTerm", "iol_term", default="")).strip()
        sources = _source_fields(mapping)
        if not target or not sources:
            continue

        present = [field for field in sources if field in result.columns]
        if not present:
            continue

        if mapping_type == "DIRECT":
            source = present[0]
            result[target] = result[source]
            if source != target:
                result.drop(columns=[source], inplace=True)
        elif mapping_type == "COALESCE":
            result[target] = result[present].bfill(axis=1).iloc[:, 0]
            for source in present:
                if source != target:
                    result.drop(columns=[source], inplace=True)
        elif mapping_type == "COMPUTED":
            expression = str(mapping.get("expression") or "").strip()
            if not expression or not re.fullmatch(r"[A-Za-z0-9_+*/(). '\"-]+", expression):
                raise ValueError(f"Expression de mapping non autorisee pour {target}")
            result[target] = result.eval(expression, engine="python")
        else:
            raise ValueError(f"Type de mapping non supporte par le moteur: {mapping_type}")

    return result
