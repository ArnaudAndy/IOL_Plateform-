# Contributing

Use Java 17 and Maven 3.9 or newer.

Before opening a pull request:

```bash
mvn test
mvn package
```

Add focused tests for every parser, validation rule and security boundary.
Fixtures must contain synthetic data only. Never commit credentials, real
health records, payment instructions or student records.

Keep canonical envelopes non-lossy: domain-specific source payloads may be
stored as JSON or XML strings, but must never be silently flattened in a way
that loses standard semantics.
