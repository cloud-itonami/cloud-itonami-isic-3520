(ns gas.governor
  "Gas Safety Governor -- the independent compliance layer that earns
  the Gas Supply Advisor the right to commit. The LLM has no notion of
  gas-safety standards, whether a customer is actually eligible for
  provisioning, or when a supply provisioning or suspension is a real-world
  actuation (not a draft), so this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD.

  HARD violations (a human approver CANNOT override):
    1. Spec-basis       -- no official jurisdiction citation
    2. Evidence incomplete -- for actuation, required evidence checklist
    3. Protected recipient -- customer has life-support/critical-infra meter
    4. Already provisioned/suspended -- double-provisioning/suspension guard

  SOFT violation (can be approved by human):
    5. Confidence floor / actuation gate -- low confidence OR real actuation"
  (:require [gas.facts :as facts]
            [gas.registry :as registry]
            [gas.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Provisioning and suspending gas supply are the real-world actuation
  events this actor performs -- a two-member set."
  #{:actuation/provision-supply :actuation/suspend-supply})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A provisioning/suspension proposal with no spec-basis citation is a
  HARD violation -- never invent a jurisdiction's requirements."
  [proposal _st]
  (let [op (:op proposal)
        value (:value proposal)]
    (when (contains? #{:actuation/provision-supply :actuation/suspend-supply} op)
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式な仕様基準の引用が無い提案は処理できない"}]))))

(defn- evidence-incomplete-violations
  "For actuation, the required evidence checklist must be satisfied."
  [{:keys [op subject]} st]
  (when (contains? #{:actuation/provision-supply :actuation/suspend-supply} op)
    (let [customer (store/customer st subject)
          verification (store/meter-verification-of st subject)]
      (when-not (and verification
                     (facts/required-evidence-satisfied?
                      (:jurisdiction customer) (:checklist verification)))
        [{:rule :evidence-incomplete
          :detail "顧客検証が完了していないか、必要な証拠が不足している"}]))))

(defn- protected-recipient-violations
  "A customer with a protected-recipient meter (life-support or
  critical-infrastructure) can NEVER be suspended."
  [{:keys [op subject]} st]
  (when (= op :actuation/suspend-supply)
    (let [customer (store/customer st subject)]
      (registry/protected-recipient-violations {:subject subject :op op} st
        (:protected-recipient? customer)))))

(defn- already-provisioned-violations
  "Guard against double provisioning."
  [{:keys [op subject]} st]
  (when (= op :actuation/provision-supply)
    (when (store/customer-already-provisioned? st subject)
      [{:rule :already-provisioned
        :detail "このメーターの供給は既にプロビジョニングされている"}])))

(defn- already-suspended-violations
  "Guard against double suspension."
  [{:keys [op subject]} st]
  (when (= op :actuation/suspend-supply)
    (when (store/customer-already-suspended? st subject)
      [{:rule :already-suspended
        :detail "このメーターの供給は既に停止されている"}])))

(defn- confidence-gate-violations
  "Low confidence or high-stakes actuation -> escalate to human."
  [{:keys [op]} {:keys [value]}]
  (let [confidence (:confidence value 0.5)]
    (when (or (< confidence confidence-floor)
              (contains? high-stakes op))
      [{:rule :escalate
        :detail (if (< confidence confidence-floor)
                  (str "信頼度が低い (confidence=" confidence ")")
                  "実際の供給操作には人間の承認が必要")}])))

;; ----------------------------- governor evaluation -----------------------------

(defn evaluate
  "Evaluate a proposal against all hard and soft gates.
  Returns a map:
    {:holds? boolean
     :hard-violations [...]
     :soft-violations [...]}"
  [proposal st]
  (let [hard-checks [spec-basis-violations
                     evidence-incomplete-violations
                     protected-recipient-violations
                     already-provisioned-violations
                     already-suspended-violations]
        soft-checks [confidence-gate-violations]
        hard-violations (mapcat #(% proposal st) hard-checks)
        soft-violations (mapcat #(% proposal (:value proposal)) soft-checks)]
    {:holds? (seq hard-violations)
     :hard-violations (vec hard-violations)
     :soft-violations (vec soft-violations)
     :clean? (and (empty? hard-violations) (empty? soft-violations))}))
