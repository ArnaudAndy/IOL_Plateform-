#!/usr/bin/env python3
"""Fail-closed validation of the resolved IOL production topology."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / "backend"
ENV_FILE = BACKEND / ".env.production"


def compose_json(*files: Path, profiles: tuple[str, ...] = ()) -> dict:
    command = ["docker", "compose", "--env-file", str(ENV_FILE)]
    for profile in profiles:
        command.extend(["--profile", profile])
    for file in files:
        command.extend(["-f", str(file)])
    command.extend(["config", "--format", "json"])
    result = subprocess.run(
        command,
        cwd=BACKEND,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "docker compose config a echoue")
    return json.loads(result.stdout)


def env(service: dict, key: str) -> str:
    value = service.get("environment", {}).get(key, "")
    return "" if value is None else str(value)


def command_text(service: dict) -> str:
    value = service.get("command", [])
    if isinstance(value, list):
        return " ".join(str(item) for item in value)
    return str(value)


def require(errors: list[str], condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)


def validate_main(config: dict, errors: list[str]) -> None:
    services = config.get("services", {})
    expected = {
        "api-core", "source-gateway", "pipeline-consumer", "nginx", "postgres",
        "kafka", "kafka-2", "kafka-3", "kafka-init",
        "mongodb", "mongodb-2", "mongodb-3", "mongodb-init",
        "rustfs", "rustfs-2", "rustfs-3", "rustfs-4", "rustfs-lb", "rustfs-init",
        "vault-rustfs-token-renewer",
        "keycloak", "keycloak-1", "keycloak-2", "keycloak-bootstrap",
    }
    require(errors, expected <= services.keys(),
            f"Services de production manquants: {sorted(expected - services.keys())}")
    if not expected <= services.keys():
        return

    api = services["api-core"]
    require(errors, env(api, "SPRING_PROFILES_ACTIVE") == "prod", "api-core doit utiliser le profil prod")
    require(errors, env(api, "AUTH_MODE") == "KEYCLOAK", "api-core doit utiliser Keycloak")
    require(errors, env(api, "CREDENTIAL_PROVIDER") == "VAULT_TRANSIT", "Vault Transit doit chiffrer les credentials")
    require(errors, env(api, "CREDENTIAL_ALLOW_LEGACY_PLAINTEXT") == "false", "Le plaintext legacy doit etre refuse")
    require(errors, "sslmode=verify-full" in env(api, "POSTGRES_URL"), "PostgreSQL API doit verifier le certificat")
    require(errors, "tls=true" in env(api, "MONGODB_URI"), "MongoDB API doit imposer TLS")
    require(errors, env(api, "SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL") == "SSL", "Kafka API doit utiliser mTLS")
    require(errors, env(api, "OBJECT_STORAGE_ENDPOINT").startswith("https://"), "RustFS API doit utiliser HTTPS")
    require(errors, env(api, "APP_INTEROP_INTERNAL_SECRET") == "", "Le secret partage interop doit etre desactive")
    require(errors, "iol-vault" in api.get("networks", {}), "api-core doit joindre Vault par le reseau dedie")
    for key in ("POSTGRES_PASSWORD", "OBJECT_STORAGE_ACCESS_KEY", "OBJECT_STORAGE_SECRET_KEY", "GEMINI_API_KEY", "GROQ_API_KEY"):
        require(errors, env(api, key) == "", f"{key} ne doit pas etre injecte en clair dans api-core")

    consumer = services["pipeline-consumer"]
    require(errors, env(consumer, "SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL") == "SSL", "Le consumer doit utiliser Kafka mTLS")
    require(errors, int(env(consumer, "APP_KAFKA_MAX_POLL_INTERVAL_MS") or "0") >= 86_400_000,
            "Le consumer doit couvrir au moins 24 h avant rebalance Kafka")
    max_poll_ms = int(env(consumer, "APP_KAFKA_MAX_POLL_INTERVAL_MS") or "0")
    hop_timeout_ms = int(env(consumer, "HOP_EXECUTION_TIMEOUT_SECONDS") or "0") * 1_000
    spark_timeout_ms = int(env(consumer, "SPARK_EXECUTION_TIMEOUT_SECONDS") or "0") * 1_000
    require(errors, hop_timeout_ms > 0 and spark_timeout_ms > 0,
            "Les timeouts Hop et Spark doivent etre explicites en production")
    require(errors, max_poll_ms > max(hop_timeout_ms, spark_timeout_ms),
            "max.poll.interval doit depasser les timeouts des moteurs")
    require(errors, "tls=true" in env(consumer, "MONGODB_URI"), "MongoDB consumer doit imposer TLS")
    require(errors, "replicaSet=rs-iol" in env(consumer, "MONGODB_URI"), "Le consumer doit utiliser le replica set MongoDB")
    require(errors, env(consumer, "WORKFLOW_SERVICE_URL").startswith("https://"), "Le consumer doit appeler l API en HTTPS")
    require(errors, env(consumer, "KEYCLOAK_TOKEN_URI").startswith("https://"), "Le consumer doit joindre Keycloak en HTTPS")
    require(errors, env(consumer, "APP_INTERNAL_SECRET") == "", "Le consumer doit utiliser OAuth2, pas un secret partage")

    gateway = services["source-gateway"]
    require(errors, int(env(gateway, "APP_KAFKA_MAX_POLL_INTERVAL_MS") or "0") >= 86_400_000,
            "Le source-gateway doit couvrir au moins 24 h avant rebalance Kafka")

    for name in ("kafka", "kafka-2", "kafka-3"):
        service = services[name]
        require(errors, env(service, "KAFKA_SSL_CLIENT_AUTH") == "required", f"{name} doit exiger les certificats clients")
        require(errors, env(service, "KAFKA_ALLOW_EVERYONE_IF_NO_ACL_FOUND") == "false", f"{name} doit refuser en absence d ACL")
        require(errors, env(service, "KAFKA_DEFAULT_REPLICATION_FACTOR") == "3", f"{name}: replication-factor doit valoir 3")
        require(errors, env(service, "KAFKA_MIN_INSYNC_REPLICAS") == "2", f"{name}: min ISR doit valoir 2")

    for name in ("mongodb", "mongodb-2", "mongodb-3"):
        cmd = command_text(services[name])
        require(errors, "--replSet rs-iol" in cmd, f"{name} doit appartenir au replica set")
        require(errors, "--auth" in cmd and "--tlsMode requireTLS" in cmd, f"{name} doit imposer auth et TLS")

    rustfs_nodes = ("rustfs", "rustfs-2", "rustfs-3", "rustfs-4")
    for name in rustfs_nodes:
        service = services[name]
        require(errors, env(service, "RUSTFS_KMS_ENABLE") == "true", f"{name} doit chiffrer via KMS")
        require(errors, env(service, "RUSTFS_KMS_BACKEND") == "vault-transit", f"{name} doit utiliser Vault Transit KMS")
        require(errors, env(service, "RUSTFS_KMS_VAULT_ADDRESS") == "https://vault:8202",
                f"{name} doit utiliser le listener KMS Vault interne")
        require(errors, "iol-vault" in service.get("networks", {}), f"{name} doit utiliser le reseau Vault dedie")
        require(errors, "vault-rustfs-token-renewer" in service.get("depends_on", {}),
                f"{name} doit attendre le renouvellement du jeton Vault")
        require(errors, command_text(service).count("https://rustfs") == 4, f"{name} doit declarer les quatre noeuds distribues")

    rustfs_init = services["rustfs-init"]
    require(errors, "rustfs-lb" in rustfs_init.get("depends_on", {}),
            "Le provisioning du bucket doit attendre RustFS")
    require(errors, env(rustfs_init, "RUSTFS_ENDPOINT").startswith("https://"),
            "Le provisioning RustFS doit utiliser HTTPS")
    for application in (api, consumer, services["source-gateway"]):
        require(errors, "rustfs-init" in application.get("depends_on", {}),
                "Chaque composant objet doit attendre le provisioning RustFS")

    renewer = services["vault-rustfs-token-renewer"]
    require(errors, env(renewer, "VAULT_ADDR") == "https://vault:8200",
            "Le renouvelleur RustFS doit joindre le listener Vault mTLS")
    require(errors, bool(env(renewer, "VAULT_CLIENT_CERT")) and bool(env(renewer, "VAULT_CLIENT_KEY")),
            "Le renouvelleur RustFS doit presenter un certificat client")
    require(errors, "iol-vault" in renewer.get("networks", {}),
            "Le renouvelleur RustFS doit rester sur le reseau Vault dedie")

    for name in ("keycloak-1", "keycloak-2"):
        service = services[name]
        require(errors, env(service, "KC_HTTPS_CLIENT_AUTH") == "required", f"{name} doit imposer mTLS")
        require(errors, env(service, "KC_CACHE_EMBEDDED_MTLS_ENABLED") == "true",
                f"{name} doit chiffrer le transport du cache")
        require(errors, "sslmode=verify-full" in env(service, "KC_DB_URL"), f"{name} doit verifier PostgreSQL")
        require(errors, env(service, "KC_HTTP_ENABLED") == "false", f"{name} ne doit pas accepter HTTP")
        require(errors, env(service, "KC_HTTP_MANAGEMENT_HOST") == "127.0.0.1",
                f"{name}: le management HTTP doit rester local")
        require(errors, "/health/ready" in json.dumps(service.get("healthcheck", {})),
                f"{name} doit utiliser la readiness Keycloak")

    bootstrap = services["keycloak-bootstrap"]
    require(errors, bootstrap.get("profiles") == ["bootstrap"],
            "Le bootstrap Keycloak doit rester une ceremonie explicite")
    require(errors, env(bootstrap, "KEYCLOAK_REMOVE_BOOTSTRAP_ADMIN") == "true",
            "Le compte administrateur Keycloak temporaire doit etre supprime")
    require(errors, env(bootstrap, "KEYCLOAK_SMTP_PASSWORD_FILE").startswith("/run/secrets/"),
            "Le mot de passe SMTP Keycloak doit venir d un secret fichier")
    for name in ("api-core", "pipeline-consumer"):
        dependencies = services[name].get("depends_on", {})
        require(errors, "keycloak" in dependencies,
                f"{name} doit attendre la readiness Keycloak")
        require(errors, "keycloak-bootstrap" not in dependencies,
                f"{name} ne doit pas dependre du profil bootstrap non rejouable")

    published = {name for name, service in services.items() if service.get("ports")}
    require(errors, published <= {"nginx"}, f"Ports internes publies: {sorted(published - {'nginx'})}")
    require(errors, config.get("networks", {}).get("iol-internal", {}).get("internal") is True,
            "Le reseau iol-internal doit etre isole")
    require(errors, config.get("networks", {}).get("iol-vault", {}).get("external") is True,
            "Le reseau Vault partage doit etre declare externe")

    for name, service in services.items():
        image = str(service.get("image", ""))
        require(errors, not image.endswith(":latest"), f"Image mutable latest interdite: {name}")

    # Le magasin d'objets porte des donnees metier en transit. Il est deploye en
    # version pre-GA par choix assume: la contrepartie est que la version doit
    # etre FIGEE et QUALIFIEE, jamais flottante. Un pre-GA qui bouge d'un
    # deploiement a l'autre change de comportement sans revue.
    object_store_images = {
        str(services[name].get("image", ""))
        for name in ("rustfs", "rustfs-2", "rustfs-3", "rustfs-4")
        if name in services
    }
    require(errors, len(object_store_images) <= 1,
            f"Les noeuds du magasin d objets doivent partager la meme version: {sorted(object_store_images)}")
    for image in object_store_images:
        require(errors, ":" in image and not image.endswith(":latest"),
                f"Magasin d objets sans version figee: {image}")
        # Une version pre-GA impose la preuve de qualification: mode distribue,
        # integrite apres perte d un noeud et chiffrement au repos.
        if any(token in image.lower() for token in ("alpha", "beta", "-rc", "preview", "snapshot")):
            proof = BACKEND / "ops/tests/rustfs-qualification.sh"
            require(errors, proof.exists(),
                    f"Magasin d objets en version pre-GA ({image}): le script de "
                    f"qualification {proof.name} est obligatoire et doit etre execute "
                    f"sur l infrastructure cible avant la mise en production.")
    web_volumes = json.dumps(services["nginx"].get("volumes", []))
    require(errors, "frontend/dist" not in web_volumes, "Le frontend de production doit venir d une image immuable")

    # Les moteurs Python realisent la transformation des donnees. Montes depuis
    # l'hote, ils echappent a la signature cosign, aux SBOM et au scan Trivy, et
    # un retour arriere de version ne les ramene pas. Ils doivent venir de
    # l'image, au meme titre que le frontend.
    consumer_volumes = json.dumps(services["pipeline-consumer"].get("volumes", []))
    for engine in ("moteur_universel.py", "spark_etl.py", "mapping_engine.py"):
        require(errors, engine not in consumer_volumes,
                f"{engine} doit venir de l image immuable, pas d un montage hote")
    require(errors, "/opt/iol/project" not in consumer_volumes,
            "Le projet Hop doit venir de l image immuable, pas d un montage hote")


def validate_source_gateway(config: dict, errors: list[str]) -> None:
    """Confinement du service de lecture des sources.

    Le gateway est le seul composant autorise a lire les sources. La porte CI
    verrouille son confinement et refuse qu'il disparaisse de la topologie.
    """
    services = config.get("services", {})
    gateway = services.get("source-gateway")
    if not gateway:
        errors.append("source-gateway absent de la topologie de production")
        return

    # Aucune interface publique: c'est la raison d'etre du service.
    require(errors, not gateway.get("ports"),
            "source-gateway ne doit publier aucun port")
    require(errors, "tls=true" in env(gateway, "MONGODB_URI"),
            "source-gateway doit imposer TLS vers MongoDB")
    require(errors, env(gateway, "SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL") == "SSL",
            "source-gateway doit utiliser Kafka mTLS")
    require(errors, env(gateway, "OBJECT_STORAGE_ENDPOINT").startswith("https://"),
            "source-gateway doit utiliser RustFS en HTTPS")
    require(errors, env(gateway, "OBJECT_STORAGE_CREATE_BUCKET_IF_MISSING") == "false",
            "source-gateway ne doit pas provisionner son bucket en production")
    require(errors, env(gateway, "CREDENTIAL_PROVIDER") == "VAULT_TRANSIT",
            "source-gateway doit dechiffrer via Vault Transit")
    require(errors, env(gateway, "SPRING_PROFILES_ACTIVE") == "prod",
            "source-gateway doit utiliser le profil prod")
    require(errors, "iol-vault" in gateway.get("networks", {}),
            "source-gateway doit joindre Vault par le reseau dedie")
    for key in ("OBJECT_STORAGE_ACCESS_KEY", "OBJECT_STORAGE_SECRET_KEY", "MONGODB_PASSWORD"):
        require(errors, env(gateway, key) == "",
                f"{key} ne doit pas etre injecte en clair dans source-gateway")
    replicas = gateway.get("deploy", {}).get("replicas", 1)
    require(errors, int(replicas) >= 2,
            "source-gateway doit avoir au moins deux replicas en production")

    # Le volume d'uploads doit rester en lecture seule: api-core depose et
    # scanne, le gateway ne fait que lire un fichier deja declare sain.
    for volume in gateway.get("volumes", []):
        target = volume.get("target") if isinstance(volume, dict) else str(volume)
        read_only = volume.get("read_only") if isinstance(volume, dict) else ":ro" in str(volume)
        if target and "uploads" in str(target):
            require(errors, bool(read_only),
                    "source-gateway doit monter les uploads en lecture seule")

    # Les deux services ne doivent jamais partager la meme identite Vault.
    api = services.get("api-core", {})
    gateway_role = env(gateway, "VAULT_ROLE_ID_FILE")
    api_role = env(api, "VAULT_ROLE_ID_FILE")
    if gateway_role and api_role:
        require(errors, gateway_role != api_role,
                "source-gateway et api-core doivent utiliser des identites Vault distinctes")


def validate_source_gateway_policy(errors: list[str]) -> None:
    """La politique Vault du gateway ne doit permettre que le dechiffrement.

    Pouvoir chiffrer reviendrait a pouvoir enregistrer ou reecrire un secret de
    connexion, ce qui reste une operation d'api-core declenchee par un
    utilisateur authentifie.
    """
    policy_path = BACKEND / "vault/policies/iol-source-gateway.hcl"
    if not policy_path.exists():
        errors.append("Politique Vault du source-gateway absente")
        return
    policy = policy_path.read_text(encoding="utf-8")
    require(errors, "transit/decrypt/iol-business-credentials" in policy,
            "Le source-gateway doit pouvoir dechiffrer les credentials source")
    require(errors, "transit/encrypt/" not in policy,
            "Le source-gateway ne doit PAS pouvoir chiffrer de nouveaux credentials")
    require(errors, "transit/rewrap/" not in policy,
            "Le source-gateway ne doit PAS pouvoir reencapsuler un credential")


def validate_openhim(config: dict, errors: list[str]) -> None:
    services = config.get("services", {})
    require(errors, "openhim-mongo" not in services and "openhim-mongo-2" not in services,
            "OpenHIM production ne doit pas redemarrer son ancien MongoDB local")
    core = services.get("openhim-core", services.get("openhim", {}))
    if not core:
        errors.append("Service OpenHIM Core introuvable dans l overlay de production")
        return
    mongo_url = env(core, "mongo_url") or env(core, "MONGO_URL")
    if "tls=true" not in mongo_url or "replicaSet=rs-iol" not in mongo_url:
        mongo_url += (BACKEND / "openhim/openhim-core-privacy/secure-entrypoint.sh").read_text(
            encoding="utf-8"
        )
    require(errors, "tls=true" in mongo_url and "replicaSet=rs-iol" in mongo_url,
            "OpenHIM doit utiliser le replica set MongoDB TLS commun")
    openid = env(core, "api_openid_url")
    require(errors, openid.startswith("https://"), "OpenHIM doit deleguer l authentification a Keycloak HTTPS")


def validate_shared_networks(main_config: dict, openhim_config: dict, errors: list[str]) -> None:
    """OpenHIM est une stack Compose distincte, mais ne doit jamais etre isole.

    Nginx et les mediateurs communiquent par les deux reseaux nommes de la
    plateforme. Une valeur par defaut liee au nom du projet Compose pouvait les
    faire diverger silencieusement entre preproduction et production.
    """
    main_networks = main_config.get("networks", {})
    openhim_networks = openhim_config.get("networks", {})
    for network_name in ("iol-internal", "iol-egress"):
        main_name = str(main_networks.get(network_name, {}).get("name", ""))
        openhim_name = str(openhim_networks.get(network_name, {}).get("name", ""))
        require(errors, bool(main_name) and main_name == openhim_name,
                f"OpenHIM et la stack principale doivent partager le reseau {network_name}")


def validate_files(errors: list[str]) -> None:
    realm = json.loads((BACKEND / "keycloak/realm/iol-realm.json").read_text(encoding="utf-8"))
    require(errors, realm.get("sslRequired") == "all", "Le realm Keycloak doit imposer SSL")
    require(errors, realm.get("registrationAllowed") is False, "L auto-inscription Keycloak doit etre desactivee")
    require(errors, realm.get("verifyEmail") is True, "Keycloak doit verifier les adresses e-mail")
    require(errors, realm.get("bruteForceProtected") is True, "Keycloak doit bloquer les attaques par force brute")
    require(errors, "length(14)" in realm.get("passwordPolicy", ""),
            "Keycloak doit imposer une politique de mot de passe forte")
    require(errors, realm.get("revokeRefreshToken") is True and realm.get("refreshTokenMaxReuse") == 0,
            "Les refresh tokens Keycloak doivent etre a usage unique")
    required_actions = {action.get("alias"): action for action in realm.get("requiredActions", [])}
    require(errors, required_actions.get("CONFIGURE_TOTP", {}).get("defaultAction") is True,
            "L enrôlement TOTP doit etre obligatoire")
    default_composites = realm.get("defaultRole", {}).get("composites", {}).get("realm", [])
    require(errors, "USER" not in default_composites,
            "Les comptes de service ne doivent pas heriter automatiquement du role USER")
    web = next((client for client in realm.get("clients", []) if client.get("clientId") == "iol-web"), {})
    require(errors, web.get("publicClient") is True and web.get("standardFlowEnabled") is True,
            "iol-web doit etre un client public Authorization Code + PKCE")
    require(errors, web.get("directAccessGrantsEnabled") is False,
            "Le password grant doit etre desactive pour iol-web")

    vault_template = (BACKEND / "vault/config/vault.hcl.tpl").read_text(encoding="utf-8")
    require(errors, 'storage "raft"' in vault_template, "Vault doit utiliser Raft")
    require(errors, "tls_require_and_verify_client_cert = true" in vault_template,
            "Vault doit exiger mTLS")
    require(errors, 'tls_min_version = "tls13"' in vault_template, "Vault doit imposer TLS 1.3")
    require(errors, 'address = "0.0.0.0:8202"' in vault_template,
            "Vault doit exposer le listener TLS KMS RustFS interne")

    bootstrap = (BACKEND / "vault/bootstrap-transit.sh").read_text(encoding="utf-8")
    require(errors, "secret_id_ttl=0 secret_id_num_uses=0" in bootstrap,
            "L AppRole API ne doit pas expirer sans mecanisme de rotation")
    require(errors, "-period=24h" in bootstrap and "iol-rustfs-kms" in bootstrap,
            "RustFS doit utiliser un jeton periodique identifiable")
    rustfs_policy = (BACKEND / "vault/policies/iol-rustfs.hcl").read_text(encoding="utf-8")
    require(errors, 'path "auth/token/renew-self"' in rustfs_policy,
            "La politique RustFS doit autoriser uniquement son auto-renouvellement")


def main() -> int:
    errors: list[str] = []
    try:
        main_config = compose_json(
            BACKEND / "docker-compose.yml",
            BACKEND / "docker-compose.production.yml",
            profiles=("bootstrap",),
        )
        openhim_config = compose_json(
            BACKEND / "openhim/docker-compose.openhim.yml",
            BACKEND / "openhim/docker-compose.openhim.production.yml",
        )
    except (RuntimeError, json.JSONDecodeError) as exc:
        print(f"ERREUR: {exc}", file=sys.stderr)
        return 1

    validate_main(main_config, errors)
    validate_source_gateway(main_config, errors)
    validate_source_gateway_policy(errors)
    validate_openhim(openhim_config, errors)
    validate_shared_networks(main_config, openhim_config, errors)
    validate_files(errors)

    if errors:
        print("PORTE SECURITE PRODUCTION REFUSEE:", file=sys.stderr)
        for error in errors:
            print(f" - {error}", file=sys.stderr)
        return 1
    print("PORTE SECURITE PRODUCTION VALIDEE: 3 Kafka, 3 MongoDB, 4 RustFS, Keycloak HA, Vault Raft et mTLS.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
