# Security Policy

## Reporting a vulnerability

Do not open a public issue for a vulnerability. Contact the repository owner
through GitHub Security Advisories and include:

- affected module and version;
- reproduction steps;
- impact and expected behavior;
- logs with credentials, patient data, payment data and student data removed.

## Deployment assumptions

- OpenHIM is the only public entry point.
- TLS is required at the gateway and between production components.
- OpenHIM channels use authenticated clients and default to `private`.
- Passwords and shared secrets are injected at runtime from a secret manager.
- Mediator ports are private network services.
- Logs and OpenHIM transaction bodies must not retain sensitive payloads.
- Multi-organization operation is unsupported until runtime storage, Kafka,
  object keys, credentials and authorization are isolated and tested.

Supported releases receive security fixes on the latest minor release.
