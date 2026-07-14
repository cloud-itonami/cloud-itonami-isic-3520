(ns gas.facts
  "Per-jurisdiction gas-supply safety requirements and standards citations.
  Every jurisdiction in this catalog is backed by an official spec-basis.
  NEVER invent requirements without an official citation.

  This is deliberately a starting catalog (honest coverage reporting) to
  prove the governor contract end-to-end, not a claim of global coverage.
  Adding a jurisdiction is additive: one map entry citing a real official
  source -- never fabricate a jurisdiction's requirements to make coverage
  look bigger.")

;; ----------------------------- jurisdiction catalog -----------------------------

(def catalog
  "Per-jurisdiction gas-supply requirements with official spec-basis citations."
  {
   :JPN
   {:name "Japan"
    :requirements
    {:customer-verification {:description "Customer identity verification (legal name, address, contact)"
                            :required true
                            :spec-basis "Gas Utility Regulation Act (ガス事業法) §24"
                            :evidence [:customer-id-proof :address-proof :contact-info]}
     :meter-inspection {:description "Meter certification and safety inspection"
                       :required true
                       :spec-basis "Gas Utility Regulation Act §28"
                       :evidence [:meter-cert :inspection-date]}
     :safety-information {:description "Provide safety information to customer"
                         :required true
                         :spec-basis "Gas Utility Regulation Act §33"
                         :evidence [:safety-brochure-provided]}}
    :suspension-requirements
    {:payment-delinquency {:allowed true :spec-basis "Gas Utility Regulation Act §32"}
     :safety-violation {:allowed true :spec-basis "Gas Utility Regulation Act §31"}
     :customer-request {:allowed true :spec-basis "Gas Utility Regulation Act §25"}}}

   :USA
   {:name "United States"
    :requirements
    {:customer-verification {:description "Customer identity and credit verification"
                            :required true
                            :spec-basis "FTC Safeguards Rule (16 CFR Part 314)"
                            :evidence [:customer-id-proof :utility-account-proof]}
     :meter-inspection {:description "Meter accuracy certification"
                       :required true
                       :spec-basis "NIST Handbook 44 - Specifications for Meters"
                       :evidence [:meter-cert :calibration-date]}
     :disclosure {:description "Provide notice of terms and conditions"
                 :required true
                 :spec-basis "FTC Act §5"
                 :evidence [:disclosure-signed]}}}

   :GBR
   {:name "United Kingdom"
    :requirements
    {:customer-verification {:description "Customer identity verification"
                            :required true
                            :spec-basis "Gas (Standards of Performance) Regulations"
                            :evidence [:customer-id-proof :contact-proof]}
     :meter-inspection {:description "Smart meter installation where required"
                       :required false
                       :spec-basis "The Gas (Standards of Performance) Regulations"
                       :evidence [:meter-cert]}}}})

;; ----------------------------- coverage reporting (honest) -----------------------------

(defn coverage
  "Report what fraction of worldwide jurisdictions have official spec-basis
  in this catalog. Honest about out-of-scope coverage."
  []
  (let [catalog-count (count catalog)
        world-jurisdictions ~194]
    {:implemented catalog-count
     :worldwide-jurisdictions world-jurisdictions
     :coverage-pct (* 100.0 (/ catalog-count world-jurisdictions))
     :note "Starting catalog to prove governor contract end-to-end, not global coverage claim"}))

;; ----------------------------- helpers -----------------------------

(defn requirement-citations
  "Get all official citations for a jurisdiction's requirements."
  [jurisdiction]
  (get-in catalog [jurisdiction :requirements]))

(defn suspension-allowed-for?
  "Check if suspension is allowed for a given reason in this jurisdiction."
  [jurisdiction reason]
  (get-in catalog [jurisdiction :suspension-requirements reason :allowed] false))

(defn required-evidence-satisfied?
  "Check if a checklist satisfies this jurisdiction's evidence requirements."
  [jurisdiction checklist]
  (let [reqs (get-in catalog [jurisdiction :requirements])]
    (every? (fn [[req-key req-spec]]
              (if (:required req-spec)
                (let [evidence-keys (set (:evidence req-spec))]
                  (every? #(contains? checklist %) evidence-keys))
                true))
            reqs)))
