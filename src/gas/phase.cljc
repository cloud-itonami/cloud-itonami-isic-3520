(ns gas.phase
  "Phase table: which operations are eligible for auto-commit vs
  human-approval-required at each lifecycle stage.

  Phase 0→3: read-only → intake → verification → supervised (both
  provision and suspension always human).

  CRITICAL INVARIANT: neither :actuation/provision-supply nor
  :actuation/suspend-supply is in ANY phase's :auto set. Actuation is
  ALWAYS human-approved, enforced by both this phase table AND the
  governor's confidence-gate check -- two independent layers agree that
  supply operations are never autonomous.")

(def phases
  "Phase lifecycle table. Each phase defines which operations can auto-commit.
  Actuation operations (:actuation/*) are NEVER in the :auto set --
  they always require human sign-off."
  [
   {:phase 0
    :name :read-only
    :description "Initial read-only inspection"
    :auto #{}
    :human-approval-required #{:site/view :telemetry/observe}}

   {:phase 1
    :name :intake
    :description "Customer intake and basic verification"
    :auto #{:customer/intake}
    :human-approval-required #{:meter/verify}}

   {:phase 2
    :name :verification
    :description "Full meter verification"
    :auto #{:meter/verify}
    :human-approval-required #{:actuation/provision-supply}}

   {:phase 3
    :name :supervised
    :description "Supervised - all actuation requires human sign-off"
    :auto #{}
    :human-approval-required #{:actuation/provision-supply :actuation/suspend-supply}}
   ])

(defn phase-by-number [n]
  (some #(when (= (:phase %) n) %) phases))

(defn can-auto-commit?
  "Check if an operation can auto-commit at a given phase."
  [phase-num op]
  (let [phase-def (phase-by-number phase-num)]
    (contains? (:auto phase-def) op)))

(defn can-human-approve?
  "Check if an operation can be human-approved at a given phase."
  [phase-num op]
  (let [phase-def (phase-by-number phase-num)]
    (contains? (:human-approval-required phase-def) op)))

;; CRITICAL TEST: actuation operations never auto-commit at any phase
(defn actuation-never-auto? []
  (let [actuation-ops #{:actuation/provision-supply :actuation/suspend-supply}]
    (every? (fn [phase-def]
              (empty? (set/intersection actuation-ops (:auto phase-def))))
            phases)))
