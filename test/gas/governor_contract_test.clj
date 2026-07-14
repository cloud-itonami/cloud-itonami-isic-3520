(ns gas.governor-contract-test
  (:require [clojure.test :refer [deftest is]]
            [gas.store :as store]
            [gas.advisor :as advisor]
            [gas.governor :as governor]
            [gas.registry :as registry]))

(deftest spec-basis-hard-gate
  "Spec-basis is a HARD gate: never allow proposals without official citations."
  (let [st (store/mem-store)
        proposal {:op :actuation/provision-supply
                  :subject "cust-1"
                  :value {:cites []
                          :checklist {:customer-id-proof true}
                          :confidence 0.9}
                  :cites []}]
    (let [eval (governor/evaluate proposal st)]
      (is (:holds? eval) "Proposal with empty cites should hold")
      (is (seq (:hard-violations eval)) "Should have hard violations")
      (is (some #(= (:rule %) :no-spec-basis) (:hard-violations eval))))))

(deftest protected-recipient-hard-gate
  "Protected recipient meter (life-support/critical-infrastructure) can
  NEVER be suspended, not even with human approval."
  (let [st (store/mem-store)
        ;; Hospital meter (cust-2) is marked protected-recipient? true
        proposal (registry/suspension-draft "cust-2"
                   ["Gas Utility Regulation Act §32"]
                   :payment-delinquency
                   {:customer-id-proof true :delinquency-verified true}
                   0.9
                   "Suspension due to payment")]
    (let [eval (governor/evaluate proposal st)]
      (is (:holds? eval) "Protected recipient suspension should hold")
      (is (some #(= (:rule %) :protected-recipient) (:hard-violations eval))
        "Should have protected-recipient violation"))))

(deftest already-provisioned-guard
  "Double provisioning guard: a supply can only be provisioned once."
  (let [st (store/mem-store)
        ;; Pre-provision cust-1
        _ (swap! (-> st :data) assoc-in [:customers "cust-1" :supply-provisioned?] true)
        proposal (registry/provision-draft "cust-1"
                   ["Gas Utility Regulation Act §24"]
                   {:customer-id-proof true :meter-cert true}
                   0.9
                   "Second provision attempt")]
    (let [eval (governor/evaluate proposal st)]
      (is (:holds? eval) "Double provision attempt should hold")
      (is (some #(= (:rule %) :already-provisioned) (:hard-violations eval))
        "Should have already-provisioned violation"))))

(deftest actuation-requires-escalation
  "Both provision and suspension actuation require human sign-off,
  even when all other checks are clean."
  (let [st (store/mem-store)
        adv (advisor/mock-advisor)
        provision-proposal (advisor/provision-proposal adv "cust-1")]
    (let [eval (governor/evaluate provision-proposal st)]
      (is (seq (:soft-violations eval)) "Should have soft violations for actuation")
      (is (some #(= (:rule %) :escalate) (:soft-violations eval))))))

(deftest non-protected-recipient-can-be-suspended
  "Non-protected customers CAN be suspended (human approval only, not auto)."
  (let [st (store/mem-store)
        proposal (registry/suspension-draft "cust-3"  ; industrial, not protected
                   ["Gas Utility Regulation Act §32"]
                   :payment-delinquency
                   {:customer-id-proof true :delinquency-verified true}
                   0.9
                   "Suspension for payment")]
    (let [eval (governor/evaluate proposal st)]
      (is (not (:holds? eval)) "Non-protected suspension should not hard-hold")
      (is (not (some #(= (:rule %) :protected-recipient) (:hard-violations eval)))
        "Should not have protected-recipient violation"))))
