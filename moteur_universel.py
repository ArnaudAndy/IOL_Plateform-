import sys
import os
import json
import pandas as pd
from sqlalchemy import create_engine, inspect, text
import base64
import csv
import datetime
import io
import unicodedata
import re
import uuid
from urllib.parse import quote_plus
import builtins
from itertools import chain
from mapping_engine import apply_workflow_mappings


def _ascii_safe_log_value(value):
    return str(value).encode("ascii", "replace").decode("ascii")


def print(*args, **kwargs):
    kwargs.setdefault("flush", True)
    builtins.print(*[_ascii_safe_log_value(arg) for arg in args], **kwargs)

# =============================================================================
# CRITICAL SYNC POINT
# clean_column_name() logic MUST match byte-for-byte with ColumnNameSanitizer.java
# Any change here requires an equivalent change in Java.
# Reference test cases (verify BOTH sides produce identical output):
#   "Montant Total (€)" -> "montant_total_"
#   "Date_Op"           -> "date_op"
#   "123_column"        -> "c_123_column"
#   "Customer@Name#2"   -> "customer_name_2"
# =============================================================================
def clean_column_name(raw):
    """
    Sanitizes a single column name to match the Bronze layer schema.

    Steps:
      1. Convert to ASCII (remove accents: é -> e)
      2. Keep only [a-zA-Z0-9_], replace others with underscore
      3. Collapse consecutive underscores, strip leading underscores
      4. Convert to lowercase
      5. Prefix with 'c_' if it starts with a digit
    """
    if raw is None or (isinstance(raw, str) and raw.strip() == ""):
        return "column"

    trimmed = raw.strip()

    # Step 1: Normalize to ASCII (decompose then drop diacritics)
    normalized = unicodedata.normalize('NFD', trimmed)
    ascii_str = normalized.encode('ascii', 'ignore').decode('ascii')

    # Step 2: keep only [a-zA-Z0-9_], everything else becomes underscore
    sanitized = re.sub(r'[^a-zA-Z0-9_]', '_', ascii_str)

    # Step 3: strip leading underscores, collapse consedcutive underscores
    sanitized = re.sub(r'^_+', '', sanitized)
    sanitized = re.sub(r'_+', '_', sanitized)

    # Step 4: lowercase
    sanitized = sanitized.lower()

    if sanitized.strip() == "":
        return "column"

    # Step 5: prefix if starts with a digit
    if sanitized[0].isdigit():
        sanitized = "c_" + sanitized

    return sanitized


# =============================================================================
# SECURITY HELPERS — incremental filter (prevents SQL injection)
# =============================================================================
_IDENTIFIER_RE = re.compile(r'^[a-zA-Z_][a-zA-Z0-9_]*$')

def safe_identifier(name):
    """Validates a SQL identifier (column name). Raises if invalid."""
    if not name or not _IDENTIFIER_RE.match(name):
        raise ValueError(f"Nom de colonne incrémentale invalide: {name!r}")
    return name

def safe_watermark(value):
    """
    Validates a watermark value: only digits, '-', ':', 'T', '.', ' ', '+', 'Z'
    are allowed (covers dates, datetimes, ISO-8601 and numeric ids).
    """
    if value is None:
        return None
    s = str(value)
    if not re.match(r'^[0-9\-:T.\s+Zz]+$', s):
        raise ValueError(f"Valeur watermark invalide: {value!r}")
    return s


def has_unresolved_placeholder(value):
    """True when Hop/Spring left a ${VAR} placeholder unresolved."""
    return isinstance(value, str) and "${" in value
def build_incremental_query(base_query, inc_col, watermark):
    """
    Wraps the base query with an incremental WHERE clause, safely.
    Uses a subquery so it works whether or not base_query already has a WHERE.
    """
    col = safe_identifier(inc_col)
    wm = safe_watermark(watermark)
    # Subquery wrapping is the safest way to avoid breaking an existing WHERE/GROUP BY
    return f"SELECT * FROM ({base_query}) AS _src WHERE {col} > '{wm}'"


def _quote_identifier(value):
    return '"' + str(value).replace('"', '""') + '"'


def _postgres_copy(frame, engine, target_schema, target_name, if_exists):
    # Let pandas/SQLAlchemy create or replace the table schema, then stream rows
    # with PostgreSQL COPY instead of issuing one INSERT batch at a time.
    frame.head(0).to_sql(
        target_name,
        engine,
        schema=target_schema,
        if_exists=if_exists,
        index=False,
    )
    qualified_table = (
        f"{_quote_identifier(target_schema)}.{_quote_identifier(target_name)}"
        if target_schema else _quote_identifier(target_name)
    )
    columns = ", ".join(_quote_identifier(column) for column in frame.columns)
    buffer = io.StringIO()
    frame.to_csv(
        buffer,
        index=False,
        header=False,
        na_rep="\\N",
        quoting=csv.QUOTE_MINIMAL,
    )
    buffer.seek(0)
    raw_connection = engine.raw_connection()
    try:
        cursor = raw_connection.cursor()
        try:
            cursor.copy_expert(
                f"COPY {qualified_table} ({columns}) FROM STDIN "
                "WITH (FORMAT CSV, NULL '\\N')",
                buffer,
            )
            raw_connection.commit()
        finally:
            cursor.close()
    except Exception:
        raw_connection.rollback()
        raise
    finally:
        raw_connection.close()


def write_bronze_frame(
        frame, engine, target_db_type, target_schema, target_name,
        write_mode, sql_batch_rows, strategy):
    normalized = str(strategy or "AUTO").upper()
    if normalized == "AUTO":
        if target_db_type == "POSTGRES":
            normalized = "POSTGRES_COPY"
        elif target_db_type in ("MYSQL", "MARIADB", "SQLITE"):
            normalized = "MULTI"
        else:
            normalized = "INSERT_BATCH"

    if normalized == "POSTGRES_COPY" and target_db_type == "POSTGRES":
        _postgres_copy(frame, engine, target_schema, target_name, write_mode)
        return normalized

    method = "multi" if normalized == "MULTI" else None
    frame.to_sql(
        target_name,
        engine,
        schema=target_schema,
        if_exists=write_mode,
        index=False,
        chunksize=sql_batch_rows,
        method=method,
    )
    return normalized


def _qualified_table(engine, schema, table):
    preparer = engine.dialect.identifier_preparer
    quoted_table = preparer.quote_identifier(table)
    return f"{preparer.quote_schema(schema)}.{quoted_table}" if schema else quoted_table


def _rename_table(connection, engine, db_type, schema, source_name, target_name):
    source = _qualified_table(engine, schema, source_name)
    quoted_target = engine.dialect.identifier_preparer.quote_identifier(target_name)
    if db_type in ('MYSQL', 'MARIADB'):
        target = _qualified_table(engine, schema, target_name)
        connection.execute(text(f"RENAME TABLE {source} TO {target}"))
    elif db_type == 'MSSQL':
        qualified_source = f"{schema}.{source_name}" if schema else source_name
        escaped_source = qualified_source.replace("'", "''")
        escaped_target = target_name.replace("'", "''")
        connection.execute(text(f"EXEC sp_rename '{escaped_source}', '{escaped_target}'"))
    else:
        connection.execute(text(f"ALTER TABLE {source} RENAME TO {quoted_target}"))


def promote_staging_table(engine, db_type, schema, staging_name, target_name, write_mode):
    """Publishes a complete staging table while preserving the previous target on failure."""
    inspector = inspect(engine)
    target_exists = inspector.has_table(target_name, schema=schema)
    staging = _qualified_table(engine, schema, staging_name)
    target = _qualified_table(engine, schema, target_name)

    if write_mode == 'append' and target_exists:
        with engine.begin() as connection:
            connection.execute(text(f"INSERT INTO {target} SELECT * FROM {staging}"))
            connection.execute(text(f"DROP TABLE {staging}"))
        return

    if not target_exists:
        with engine.begin() as connection:
            _rename_table(connection, engine, db_type, schema, staging_name, target_name)
        return

    if db_type in ('MYSQL', 'MARIADB'):
        backup_name = f"_iol_old_{target_name[:30]}_{uuid.uuid4().hex[:8]}"
        backup = _qualified_table(engine, schema, backup_name)
        with engine.begin() as connection:
            connection.execute(text(f"RENAME TABLE {target} TO {backup}, {staging} TO {target}"))
        with engine.begin() as connection:
            connection.execute(text(f"DROP TABLE {backup}"))
        return

    if db_type == 'SNOWFLAKE':
        with engine.begin() as connection:
            connection.execute(text(f"ALTER TABLE {target} SWAP WITH {staging}"))
            connection.execute(text(f"DROP TABLE {staging}"))
        return

    with engine.begin() as connection:
        connection.execute(text(f"DROP TABLE {target}"))
        _rename_table(connection, engine, db_type, schema, staging_name, target_name)


def drop_staging_table(engine, schema, staging_name):
    if engine is None or not staging_name:
        return
    try:
        if inspect(engine).has_table(staging_name, schema=schema):
            qualified = _qualified_table(engine, schema, staging_name)
            with engine.begin() as connection:
                connection.execute(text(f"DROP TABLE {qualified}"))
    except Exception as error:
        print(f"Nettoyage table de transit impossible: {error}")


# =============================================================================
# PROJECTION — sélection des colonnes brutes à extraire (fields)
#   Sélection UNIQUEMENT (pas de renommage : le renommage sémantique reste en aval).
#   Les noms sont ceux d'ORIGINE de la source (avant clean_column_name).
# =============================================================================
def normalize_fields(raw_fields):
    """
    Retourne une list[str] de colonnes à conserver, ou None si aucune projection.
    Tolère : liste JSON, chaîne JSON '["a","b"]', ou liste séparée par des virgules.
    """
    if raw_fields is None:
        return None
    if isinstance(raw_fields, str):
        s = raw_fields.strip()
        if s == "" or s == "[]":
            return None
        if s.startswith('['):
            try:
                raw_fields = json.loads(s)
            except Exception:
                raw_fields = [p.strip() for p in s.split(',')]
        else:
            raw_fields = [p.strip() for p in s.split(',')]
    result = [str(f).strip() for f in raw_fields if str(f) is not None and str(f).strip() != ""]
    return result or None


def apply_projection(df, fields, inc_col):
    """
    Ne conserve que les colonnes demandées (sélection, PAS de renommage).
    Robuste aux colonnes absentes. Règle de sûreté incrémentale : si inc_col est
    défini, il est TOUJOURS inclus pour que le filtre incrémental / le watermark ne
    casse jamais. Repli : fields None/vide -> toutes les colonnes (comportement actuel).

    NB (SQL avec query custom) : la query pilote déjà le filtrage des lignes ; ici la
    projection s'applique en sélection de colonnes sur le résultat.
    """
    if fields is None:
        return df
    wanted = list(fields)
    if inc_col and inc_col not in wanted:
        wanted.append(inc_col)
        print(f"Projection: incremental_column '{inc_col}' ajoutee d'office aux colonnes extraites")
    present = [c for c in wanted if c in df.columns]
    missing = [c for c in wanted if c not in df.columns]
    if missing:
        print(f"Projection: colonnes demandees absentes ignorees: {missing}")
    if not present:
        print("Projection: aucune colonne demandee presente -> extraction complete conservee")
        return df
    print(f"Projection appliquee: {len(present)}/{len(df.columns)} colonne(s) conservee(s)")
    return df[present]


# =============================================================================
# 1. ARGUMENTS (passed by Hop "Execute a process")
#    argv[1]=proto  argv[2]=uri  argv[3]=target_table  argv[4]=options_base64
# =============================================================================
try:
    proto = sys.argv[1]
    uri = sys.argv[2]
    target_table = sys.argv[3]

    base64_args = sys.argv[4]
    decoded_bytes = base64.b64decode(base64_args)
    options = json.loads(decoded_bytes.decode('utf-8'))

    print(f"Arguments reçus: proto={proto}, target_table={target_table}")
except Exception as e:
    print(f"Erreur lors de la récupération des arguments : {e}")
    sys.exit(1)

df = None
df_stream = None
source_engine = None
source_db_connection = None
bronze_engine = None
staging_name = None
staging_schema = None
staging_promoted = False
source_label = uri
print(f"Extraction depuis {proto}: {source_label}")

# Incremental params (read once, used both at extraction and for logging)
incremental_column = options.get('incremental_column')
last_watermark = options.get('last_watermark')

try:
    # L'accès JDBC source appartient exclusivement à api-core. Le moteur reçoit
    # uniquement un artefact reconstruit depuis Kafka ou RustFS.
    if proto in ['POSTGRES', 'MYSQL', 'ORACLE', 'MSSQL', 'MARIADB', 'SQLITE', 'SNOWFLAKE', 'REDSHIFT']:
        raise ValueError(
            "Acces JDBC source direct interdit: api-core doit transporter les donnees via Kafka ou RustFS."
        )

    # --- SECTION B : FICHIERS TABULAIRES (CSV, EXCEL) ---
    elif proto == 'CSV':
        try:
            encodings_to_try = [options.get('encoding'), 'utf-8', 'ISO-8859-1', 'latin-1', 'cp1252']
            try:
                configured_chunk_rows = int(options.get('pandas_chunk_rows', 50000))
            except (TypeError, ValueError):
                configured_chunk_rows = 50000
            chunk_rows = max(1000, min(configured_chunk_rows, 250000))
            stream_csv = str(options.get('data_transport', '')).upper() == 'KAFKA_CHUNKED'
            success = False
            for enc in encodings_to_try:
                if not enc:
                    continue
                try:
                    read_options = {
                        'sep': options.get('delimiter', ';'),
                        'encoding': enc,
                        'index_col': None,
                        'dtype': str,
                    }
                    if stream_csv:
                        reader = pd.read_csv(uri, chunksize=chunk_rows, **read_options)
                        first_chunk = next(reader)
                        df_stream = chain((first_chunk,), reader)
                        print(f"CSV STREAMING: lots de {chunk_rows} lignes avec encoding {enc}")
                    else:
                        df = pd.read_csv(uri, **read_options)
                        print(f"CSV SUCCESS: {len(df)} lignes avec encoding {enc}")
                    success = True
                    break
                except (UnicodeDecodeError, TypeError, StopIteration):
                    continue
            if not success:
                raise ValueError("Impossible de lire le fichier CSV avec les encodages standards")
        except Exception as e:
            print(f"CSV ERREUR: {str(e).encode('ascii', 'ignore').decode('ascii')}")
            raise

    elif proto == 'JSON':
        try:
            try:
                configured_chunk_rows = int(options.get('pandas_chunk_rows', 50000))
            except (TypeError, ValueError):
                configured_chunk_rows = 50000
            chunk_rows = max(1000, min(configured_chunk_rows, 250000))
            reader = pd.read_json(uri, lines=True, chunksize=chunk_rows)
            first_chunk = next(reader, None)
            if first_chunk is None:
                df = pd.DataFrame()
            else:
                df_stream = chain((first_chunk,), reader)
            print(f"JSON LINES STREAMING: lots de {chunk_rows} lignes")
        except Exception as e:
            print(f"JSON ERREUR: {str(e).encode('ascii', 'ignore').decode('ascii')}")
            raise

    elif proto == 'EXCEL':
        try:
            sheet_name = options.get('sheet_name', 0)
            df = pd.read_excel(uri, sheet_name=sheet_name)
            print(f"EXCEL SUCCESS: {len(df)} lignes depuis feuille {sheet_name}")
        except Exception as e:
            print(f"EXCEL ERREUR: {str(e).encode('ascii', 'ignore').decode('ascii')}")
            raise

    # --- SECTION C : FICHIERS COLONNAIRES (PARQUET, AVRO, ORC) ---
    elif proto in ['PARQUET', 'AVRO', 'ORC']:
        try:
            if proto == 'PARQUET':
                df = pd.read_parquet(uri)
                print(f"PARQUET SUCCESS: {len(df)} lignes")
            elif proto == 'AVRO':
                import pandavro as pdx
                df = pdx.read_avro(uri)
                print(f"AVRO SUCCESS: {len(df)} lignes")
            elif proto == 'ORC':
                df = pd.read_orc(uri)
                print(f"ORC SUCCESS: {len(df)} lignes")
        except Exception as e:
            print(f"{proto} ERREUR: {str(e).encode('ascii', 'ignore').decode('ascii')}")
            raise

    else:
        print(f"ERREUR: Protocole '{proto}' non supporté")
        print("Protocoles supportés: JSON, CSV, EXCEL, PARQUET, AVRO, ORC")
        sys.exit(1)

except Exception as e:
    print(f"EXTRACTION ÉCHOUÉE: {str(e).encode('ascii', 'ignore').decode('ascii')}")
    sys.exit(1)


# =============================================================================
# SECTION E : ECRITURE DANS LA DESTINATION (Bronze)
# =============================================================================
try:
    target_conn = options.get('target_connection')
    if not target_conn or not isinstance(target_conn, dict):
        raise ValueError("target_connection manquant — connexion cible obligatoire")

    target_db_type = str(target_conn.get('db_type', 'POSTGRES')).strip().upper().replace('-', '_')
    target_db_type = {
        'POSTGRESQL': 'POSTGRES', 'PG': 'POSTGRES', 'MARIA_DB': 'MARIADB',
        'SQLSERVER': 'MSSQL', 'SQL_SERVER': 'MSSQL', 'SQLITE3': 'SQLITE',
        'AWS_REDSHIFT': 'REDSHIFT',
    }.get(target_db_type, target_db_type)
    supported_targets = ('POSTGRES', 'MYSQL', 'MARIADB', 'MSSQL', 'ORACLE', 'SQLITE', 'SNOWFLAKE', 'REDSHIFT')
    if target_db_type not in supported_targets:
        raise ValueError(
            "Destination non supportee: " + target_db_type
            + ". Destinations supportees: " + ", ".join(supported_targets)
        )

    required_fields = ['database'] if target_db_type == 'SQLITE' else ['host', 'username', 'password', 'database']
    missing_fields = [k for k in required_fields if not target_conn.get(k)]
    if missing_fields:
        raise ValueError(f"target_connection incomplet, champs manquants : {missing_fields}")

    unresolved_fields = [k for k in required_fields if has_unresolved_placeholder(target_conn.get(k))]
    if unresolved_fields:
        raise ValueError(
            f"target_connection contient des variables non résolues par Hop/Spring : {unresolved_fields}. "
            "Corriger le mapping des paramètres avant l'écriture Bronze."
        )

    db_host = str(target_conn.get('host', ''))
    db_port = str(target_conn.get('port', ''))
    db_user = str(target_conn.get('username', ''))
    # Le secret de destination n'est jamais écrit dans le JSON Kafka/Hop. Le
    # worker le reçoit à la dernière seconde via l'environnement du processus.
    db_password = os.environ.get('TARGET_PASSWORD') or str(target_conn.get('password', ''))
    db_name = str(target_conn['database'])
    db_schema = str(target_conn.get('schema') or '').strip() or None
    db_options = target_conn.get('additional_properties') or {}
    if target_db_type == 'POSTGRES':
        db_url = f"postgresql+psycopg2://{quote_plus(db_user)}:{quote_plus(db_password)}@{db_host}:{db_port}/{db_name}"
        connect_args = {"client_encoding": "utf8", "connect_timeout": 10}
    elif target_db_type in ('MYSQL', 'MARIADB'):
        db_url = f"mysql+pymysql://{quote_plus(db_user)}:{quote_plus(db_password)}@{db_host}:{db_port}/{db_name}?charset=utf8mb4"
        connect_args = {"connect_timeout": 10}
    elif target_db_type == 'MSSQL':
        db_url = f"mssql+pymssql://{quote_plus(db_user)}:{quote_plus(db_password)}@{db_host}:{db_port}/{db_name}"
        connect_args = {"login_timeout": 10}
    elif target_db_type == 'ORACLE':
        db_url = (f"oracle+oracledb://{quote_plus(db_user)}:{quote_plus(db_password)}@{db_host}:{db_port}"
                  f"/?service_name={quote_plus(db_name)}")
        connect_args = {}
    elif target_db_type == 'SQLITE':
        sqlite_path = db_name.replace('\\', '/')
        db_url = f"sqlite:///{sqlite_path}"
        connect_args = {}
    elif target_db_type == 'SNOWFLAKE':
        account = str(db_options.get('account') or db_host).replace('.snowflakecomputing.com', '')
        schema_path = f"/{quote_plus(db_schema)}" if db_schema else ''
        query_parts = []
        if db_options.get('warehouse'):
            query_parts.append('warehouse=' + quote_plus(str(db_options['warehouse'])))
        if db_options.get('role'):
            query_parts.append('role=' + quote_plus(str(db_options['role'])))
        suffix = ('?' + '&'.join(query_parts)) if query_parts else ''
        db_url = (f"snowflake://{quote_plus(db_user)}:{quote_plus(db_password)}@{account}/"
                  f"{quote_plus(db_name)}{schema_path}{suffix}")
        connect_args = {}
    else:  # REDSHIFT
        db_url = f"postgresql+psycopg2://{quote_plus(db_user)}:{quote_plus(db_password)}@{db_host}:{db_port}/{db_name}"
        connect_args = {"connect_timeout": 10}
    print(f"Connexion cible {target_db_type}: {db_host}:{db_port}/{db_name}")

    bronze_engine = create_engine(db_url, connect_args=connect_args, pool_pre_ping=True)

    load_mode = str(options.get('load_mode', 'FULL')).upper()
    explicit_write = options.get('write_mode')
    if explicit_write:
        write_mode = str(explicit_write).lower()
    else:
        write_mode = 'replace' if str(options.get('truncate', 'N')).upper() == 'Y' else 'append'
    if load_mode == 'INCREMENTAL':
        write_mode = 'append'
    if write_mode not in ('append', 'replace'):
        write_mode = 'append'

    try:
        configured_sql_batch_rows = int(options.get('sql_batch_rows', 1000))
    except (TypeError, ValueError):
        configured_sql_batch_rows = 1000
    sql_batch_rows = max(100, min(configured_sql_batch_rows, 10000))
    bulk_load_strategy = options.get('bulk_load_strategy', 'AUTO')
    projection_fields = normalize_fields(options.get('fields'))
    mapping_config = options.get('mapping_config') or []
    already_pivot = bool(options.get('already_pivot', False))
    frames = df_stream if df_stream is not None else ([df] if df is not None else [])
    batch_id = datetime.datetime.now().isoformat() if write_mode == 'append' else None
    total_rows = 0
    chunk_count = 0
    new_watermark = None

    target_schema = db_schema
    target_name = target_table
    if '.' in target_table:
        target_schema, target_name = target_table.rsplit('.', 1)
    if target_db_type in ('MYSQL', 'MARIADB', 'SQLITE'):
        target_schema = None
    staging_schema = target_schema
    staging_name = f"_iol_stage_{clean_column_name(target_name)[:28]}_{uuid.uuid4().hex[:10]}"

    print(f"Mode écriture={write_mode}" + (f", batch_id={batch_id}" if batch_id else ""))
    for frame in frames:
        if projection_fields is not None:
            frame = apply_projection(frame, projection_fields, incremental_column)

        if incremental_column and incremental_column in frame.columns and len(frame) > 0:
            raw_max = frame[incremental_column].max()
            if raw_max is not None and not pd.isna(raw_max):
                candidate = safe_watermark(raw_max)
                if new_watermark is None or candidate > new_watermark:
                    new_watermark = candidate

        if mapping_config:
            frame = apply_workflow_mappings(frame, mapping_config, already_pivot=already_pivot)

        frame['extracted_at'] = datetime.datetime.now()
        frame.columns = [clean_column_name(str(c)) for c in frame.columns]
        if batch_id:
            frame['batch_id'] = batch_id

        effective_write_mode = 'replace' if chunk_count == 0 else 'append'
        effective_strategy = write_bronze_frame(
            frame,
            bronze_engine,
            target_db_type,
            target_schema,
            staging_name,
            effective_write_mode,
            sql_batch_rows,
            bulk_load_strategy,
        )
        total_rows += len(frame)
        chunk_count += 1
        print(f"Lot Bronze {chunk_count}: {len(frame)} ligne(s), cumul={total_rows}, "
              f"chargement={effective_strategy}")

    if chunk_count == 0:
        print("Aucune donnée récupérée (DataFrame vide).")
    else:
        promote_staging_table(
            bronze_engine,
            target_db_type,
            staging_schema,
            staging_name,
            target_name,
            write_mode,
        )
        staging_promoted = True
        print(f"Publication atomique Bronze: {staging_name} -> {target_table}")
        if mapping_config:
            print(f"Mappings appliques: {len(mapping_config)} regle(s) sur {chunk_count} lot(s)")
        if incremental_column and last_watermark:
            print(f"Extraction incrémentale appliquée: {incremental_column} > {last_watermark}")
        print(f"Succès final : {total_rows} lignes dans {target_table} "
              f"(load_mode={load_mode}, write_mode={write_mode}, lots={chunk_count})")
        if new_watermark is not None:
            print(f"IOL_WATERMARK::{target_table}::{new_watermark}")
except Exception as e:
    import traceback
    print(f"Erreur d'ecriture : {repr(e)}")
    print("TRACEBACK COMPLET:")
    print(traceback.format_exc())
    sys.stdout.flush()
    sys.exit(1)
finally:
    if not staging_promoted:
        drop_staging_table(bronze_engine, staging_schema, staging_name)
    if source_db_connection is not None:
        try:
            source_db_connection.close()
        except Exception:
            pass
    if source_engine is not None:
        try:
            source_engine.dispose()
        except Exception:
            pass
    if 'bronze_engine' in globals() and bronze_engine is not None:
        try:
            bronze_engine.dispose()
        except Exception:
            pass
