(ns gas.registry
  "Draft provision/suspension records issued by the Gas Supply Advisor,
  and the governor contract they must satisfy.")

;; ----------------------------- provision/suspension drafts -----------------------------

(defn provision-draft
  "Issue a draft supply-provision record.
  - customer-id: the customer to provision
  - cites: spec-basis citations (jurisdiction/standards list)
  - checklist: evidence checklist (meter ID, customer ID proof, etc.)
  - confidence: LLM confidence (0.0-1.0)
  - notes: advisory notes"
  [customer-id cites checklist confidence notes]
  {:subject customer-id
   :op :actuation/provision-supply
   :value {:cites cites
           :spec-basis (first cites)
           :checklist checklist
           :confidence confidence
           :notes notes}
   :spec-basis (first cites)
   :cites cites})

(defn suspension-draft
  "Issue a draft supply-suspension record.
  - customer-id: the customer to suspend
  - cites: spec-basis citations (jurisdiction/standards list)
  - reason: reason for suspension (payment/safety/request)
  - checklist: evidence checklist
  - confidence: LLM confidence (0.0-1.0)
  - notes: advisory notes"
  [customer-id cites reason checklist confidence notes]
  {:subject customer-id
   :op :actuation/suspend-supply
   :value {:cites cites
           :spec-basis (first cites)
           :reason reason
           :checklist checklist
           :confidence confidence
           :notes notes}
   :spec-basis (first cites)
   :cites cites})

;; ----------------------------- governor contract helpers -----------------------------

(defn protected-recipient-violations
  "A meter marked as protected-recipient (life-support or critical
  infrastructure) can NEVER be suspended, even if all other checks pass.
  This is a HARD gate that cannot be overridden."
  [{:keys [subject op]} st protected-recipient?]
  (when (and (= op :actuation/suspend-supply) protected-recipient?)
    [{:rule :protected-recipient
      :detail "生命維持・重要インフラメーターは供給停止対象外"}]))
