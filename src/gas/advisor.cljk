(ns gas.advisor
  "Gas Supply Advisor -- drafts provision and suspension proposals.
  The advisor is sealed behind a governor (gas.governor) which
  independently verifies all proposals before any actuation.

  Mock advisor for testing and demo.")

(defprotocol Advisor
  (intake [a customer-id jurisdiction])
  (verify-meter [a customer-id jurisdiction])
  (provision-proposal [a customer-id])
  (suspension-proposal [a customer-id reason]))

;; ----------------------------- mock advisor for demo/test -----------------------------

(defrecord MockAdvisor []
  Advisor
  (intake [a customer-id jurisdiction]
    {:op :customer/intake
     :subject customer-id
     :status :success})

  (verify-meter [a customer-id jurisdiction]
    {:op :meter/verify
     :subject customer-id
     :value {:checklist {:customer-id-proof true
                         :meter-cert true
                         :address-proof true
                         :contact-info true}
             :confidence 0.85}
     :cites [(:spec-basis jurisdiction)]})

  (provision-proposal [a customer-id]
    {:op :actuation/provision-supply
     :subject customer-id
     :value {:cites ["Gas Utility Regulation Act §24"]
             :checklist {:customer-id-proof true
                        :meter-cert true
                        :address-proof true
                        :contact-info true}
             :confidence 0.9
             :notes "Customer verified, meter ready, no safety flags"}
     :spec-basis "Gas Utility Regulation Act §24"
     :cites ["Gas Utility Regulation Act §24"]})

  (suspension-proposal [a customer-id reason]
    {:op :actuation/suspend-supply
     :subject customer-id
     :value {:cites ["Gas Utility Regulation Act §32"]
             :reason reason
             :checklist {:customer-id-proof true
                        :delinquency-verified true}
             :confidence 0.8
             :notes (str "Supply suspension requested: " reason)}
     :spec-basis "Gas Utility Regulation Act §32"
     :cites ["Gas Utility Regulation Act §32"]}))

(defn mock-advisor []
  (MockAdvisor.))
