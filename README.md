# cloud-itonami-isic-3520

Open Business Blueprint for **ISIC Rev.5 3520**: manufacture of gas.

This repository publishes a community gas-supply actor -- customer intake and meter verification, gas-supply provisioning, gas-supply suspension/disconnection (with life-support safety gates), and telemetry recording -- as an OSS business that any qualified gas utility operator can fork, deploy, run, improve and sell, so municipal utilities, cooperatives, and independent operators can manage gas distribution safely, with auditable decision records and hard safety gates preventing disconnection of critical infrastructure, without renting a closed SaaS.

Built on this workspace's [`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj) StateGraph runtime (portable `.cljc`, supervised superstep loop, interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as every cloud-itonami actor (gas supply provisioning and suspension are negative actuations, requiring hard safety review before dispatch).

## Scope: what this actor does and does not do

This actor covers customer intake through meter verification, supply provisioning, and supply suspension/disconnection. It does **not** hold any distribution license required to run a gas utility in a given jurisdiction. It also does **not** model real pressure-relief systems, real gas composition/quality measurements, or the actual fluid mechanics -- no direct hardware dispatch protocol. Whoever deploys and operates a live instance (a licensed gas operator) supplies any jurisdiction-specific safety standards, the real pressure/leak monitoring and the real SCADA tooling integrations, and bears that jurisdiction's liability -- the software supplies the governed, spec-cited, audited execution scaffold so that operator does not have to build the compliance layer from scratch.

### Actuation

**Dispatching a real gas supply provisioning or suspension/disconnection is never autonomous, at any phase, by construction.** Two independent layers enforce this (`gas.governor`'s `:actuation/provision-supply`/`:actuation/suspend-supply` high-stakes gates and `gas.phase`'s phase table) -- see `gas.phase`'s docstring and test suite. The actor may draft, check and recommend; a human gas operator is always the one who actually provisions or suspends a supply.

## The core contract

```
customer intake + identity + meter verification + telemetry observation
        |
        v
   Gas Supply Advisor -> Gas Safety Governor -> supply record, suspension record, or human approval
        |
        v
robot actions (gated) + supply record + suspension record + audit ledger
```

No automated advice can provision or suspend a supply the governor refuses, approve a supply outside its verified customer scope, or publish a suspension record without governor approval and audit evidence. **A protected-recipient gate (life-support or critical-infrastructure meter) can never be suspended, even with human approval.**

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC `3520`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs

See [`docs/business-model.md`](docs/business-model.md) and [`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
