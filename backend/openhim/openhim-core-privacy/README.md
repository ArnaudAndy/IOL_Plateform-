# OpenHIM Core privacy patch

OpenHIM Core `v8.5.0` honours a channel's `requestBody=false` and
`responseBody=false` settings for the top-level transaction, but still copies
route payloads into `orchestrations`. IOL's inbound channels can carry sensitive
business data, so that behaviour does not meet the platform's retention policy.

This image applies the same channel settings to:

- mediator-provided orchestrations;
- OpenHIM route orchestrations;
- secondary route request and response bodies.

Status codes, timestamps, route names and transaction properties remain stored
for operational monitoring. The patch is tied to the exact upstream v8.5.0
implementation and deliberately fails the Docker build if that code changes.
Review or remove it when upgrading OpenHIM if the upstream release has fixed the
behaviour.
