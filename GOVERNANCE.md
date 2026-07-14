# Governance

`cloud-itonami-3520` is an OSS open-business blueprint for community gas
supply distribution operations, robotics-premised.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- a robot action the governor refuses is never dispatched to hardware.
- the Gas Safety Governor remains independent of the advisor.
- hard policy violations (out-of-scope customer provisioning, life-support
  meter disconnection, evidenceless supply record) cannot be overridden by
  human approval.
- every provisioning, suspension, sign-off and supply path is auditable.
- life-support and critical-infrastructure meters can never be suspended.
- sensitive customer and meter data stays outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or license
should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, robot-safety, audit and
data-flow review.

Certified operators can lose certification for:
- bypassing robot-safety or customer-scope checks
- mishandling customer or meter data
- suspending life-support or critical-infrastructure meters
- misrepresenting certification status
- failing to respond to safety incidents
