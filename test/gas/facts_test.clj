(ns gas.facts-test
  (:require [clojure.test :refer [deftest is]]
            [gas.facts :as facts]))

(deftest japan-jurisdiction-requirements
  "Japan (JPN) has official gas-safety requirements cited."
  (let [cites (facts/requirement-citations :JPN)]
    (is cites "Japan should have requirements")
    (is (contains? cites :customer-verification)
      "Should have customer-verification requirement")
    (is (contains? cites :meter-inspection)
      "Should have meter-inspection requirement")
    (is (every? :spec-basis (vals cites))
      "Every requirement should have an official spec-basis citation")))

(deftest suspension-allowed-check
  "Japan allows suspension for payment delinquency."
  (is (facts/suspension-allowed-for? :JPN :payment-delinquency)
    "Payment delinquency suspension should be allowed in Japan"))

(deftest germany-jurisdiction-requirements
  "Germany (DEU) has official gas-safety requirements cited (Energiewirtschaftsgesetz)."
  (let [cites (facts/requirement-citations :DEU)]
    (is cites "Germany should have requirements")
    (is (contains? cites :customer-verification)
      "Should have customer-verification requirement")
    (is (contains? cites :meter-inspection)
      "Should have meter-inspection requirement")
    (is (every? :spec-basis (vals cites))
      "Every requirement should have an official spec-basis citation")
    (is (facts/suspension-allowed-for? :DEU :payment-delinquency)
      "Payment delinquency suspension should be allowed in Germany (EnWG §41g)")))

(deftest required-evidence-satisfied
  "Check if a checklist satisfies jurisdiction requirements."
  (is (facts/required-evidence-satisfied? :JPN
        {:customer-id-proof true
         :meter-cert true
         :address-proof true
         :contact-info true
         :safety-brochure-provided true})
    "Complete checklist should satisfy all requirements")

  (is (not (facts/required-evidence-satisfied? :JPN
            {:customer-id-proof true}))
    "Incomplete checklist should not satisfy all requirements"))

(deftest france-jurisdiction-requirements
  "France (FRA) has a winter disconnection-moratorium citation (Code de l'action
  sociale et des familles Art. L115-3), distinct in shape from JPN/USA/GBR/DEU's
  customer-verification/meter-inspection/disclosure requirements."
  (let [cites (facts/requirement-citations :FRA)]
    (is cites "France should have requirements")
    (is (contains? cites :winter-disconnection-prohibition)
      "Should have winter-disconnection-prohibition requirement")
    (is (every? :spec-basis (vals cites))
      "Every requirement should have an official spec-basis citation")
    (is (facts/suspension-allowed-for? :FRA :payment-delinquency)
      "Payment-delinquency suspension is allowed in France outside the winter period")
    (is (facts/seasonal-suspension-prohibited? :FRA :payment-delinquency)
      "France blanket-prohibits payment-delinquency suspension during the winter period (1 Nov-31 Mar)")
    (is (not (facts/seasonal-suspension-prohibited? :JPN :payment-delinquency))
      "Japan has no seasonal-prohibition entry for payment-delinquency suspension")))

(deftest coverage-reporting
  "Coverage should honestly report starting catalog scope."
  (let [coverage (facts/coverage)]
    (is (< (:implemented coverage) (:worldwide-jurisdictions coverage))
      "Implemented should be less than worldwide total")
    (is (< (:coverage-pct coverage) 100)
      "Coverage percentage should be honest about partial implementation")))

(deftest jurisdiction-catalog-entries-have-citations
  "Every jurisdiction in catalog should have at least one official citation."
  (doseq [[jurisdiction jdata] facts/catalog]
    (is (contains? jdata :requirements) (str jurisdiction " should have requirements"))
    (doseq [[req-key req-spec] (:requirements jdata)]
      (is (contains? req-spec :spec-basis)
        (str jurisdiction " " req-key " should have spec-basis")))))
