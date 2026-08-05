# Publishing to GitHub and the OpenHIM Mediator Library

## Repository

Publish this directory as the root of a public GitHub repository named:

```text
openhim-mediator-iol-standard-packs
```

Do not publish the complete private IOL workspace just to expose these
mediators. Preserve this directory's commit history with a subtree split or
create a dedicated repository from an approved export.

## Release checklist

1. Run `mvn test` and `mvn package`.
2. Build all three Dockerfiles.
3. Run secret scanning and dependency review.
4. Verify that examples contain synthetic data.
5. Tag the release, for example `v1.1.0`.
6. Publish immutable container image tags and their digests.
7. Enable GitHub Security Advisories and branch protection.
8. Add repository topics: `openhim-mediator`, `openhim`, `fhir`,
   `iso-20022`, `ed-fi`, `interoperability`.
9. Confirm the public README clearly identifies the mediator endpoints.
10. Wait for the OpenHIM Mediator Library index to discover the public
    repository, then verify its card and link.

The library indexes public GitHub repositories whose names begin with
`openhim-mediator`. This monorepo produces one catalog card for the three
services. If three independent cards are required, publish each deployable
module with `mediator-runtime` in its own repository:

- `openhim-mediator-iol-fhir`
- `openhim-mediator-iol-iso20022`
- `openhim-mediator-iol-edfi`

## First production release

Use a release candidate in a non-production OpenHIM instance first. Prove:

- mediator registration and channel installation;
- private channel authentication;
- FHIR JSON/XML, ISO 20022 XML and Ed-Fi JSON/NDJSON smoke tests;
- Kafka transport below the big-data threshold;
- RustFS transport above the threshold;
- retry, DLQ and abort cleanup;
- no temporary CSV creation;
- backup restoration and rollback to the previous image digest.
