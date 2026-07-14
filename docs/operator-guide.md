# Operator Guide

## First Deployment
1. Register operator, customers/meters, staff and robots.
2. Import existing provisioning and suspension history.
3. Run read-only meter-verification and customer-intake dry-runs.
4. Configure safety-class allowed sets and human sign-off paths.
5. Publish a dry-run suspension record and audit export.

## Minimum Production Controls
- customer-identity and meter verification before any provisioning
- governor gate on every robot action before dispatch
- human sign-off for all suspension operations (especially those affecting
  critical infrastructure)
- protected-recipient audit: confirm no life-support/critical-infrastructure
  meters are included in suspension candidates
- evidence-backed provisioning and suspension records
- audit export for every provisioning, suspension and sign-off
- backup manual gas-operation process

## Certification
Certified operators must prove robot-safety integrity, customer-identity
discipline, evidence-backed provisioning/suspension records, human review
for all supply-critical actions, and explicit protection of life-support
and critical-infrastructure meters.

## Protected Recipient Policy
Meters serving the following use cases can NEVER be suspended, regardless
of circumstances:
- Hospitals and emergency medical facilities
- Fire stations and emergency services
- Water treatment and sanitation facilities
- Food preparation for vulnerable populations (nursing homes, schools, etc.)
- Residential care facilities

Any attempt to suspend such a meter will be immediately rejected by the
governor, regardless of human approval.
