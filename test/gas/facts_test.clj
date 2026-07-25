(ns gas.facts-test
  (:require [clojure.test :refer [deftest is testing]]
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

;; ───────── Evidence gate must fail closed (2026-07-25) ─────────

(deftest unknown-jurisdiction-fails-the-evidence-gate-closed
  ;; get-in returns nil for a jurisdiction absent from `catalog`, and
  ;; `(every? f nil)` is true, so this predicate used to pass VACUOUSLY --
  ;; a subject with an unrecognised jurisdiction cleared the Governor's
  ;; :evidence-incomplete gate carrying an empty checklist.
  (is (false? (facts/required-evidence-satisfied? :atlantis #{}))
      "an unknown jurisdiction must never satisfy the evidence requirements")
  (is (false? (facts/required-evidence-satisfied? :atlantis #{:anything}))
      "and must not be rescued by supplying unrelated evidence")
  (is (false? (facts/required-evidence-satisfied? nil #{}))
      "a missing jurisdiction is not a pass either"))

(deftest known-jurisdictions-still-evaluate-normally
  ;; Guards against "fixed" by making everything false.
  (let [jurisdictions (keys facts/catalog)]
    (is (seq jurisdictions) "catalog must be non-empty for this test to mean anything")
    (doseq [j jurisdictions]
      (is (boolean? (facts/required-evidence-satisfied? j #{}))
          (str j " must still produce a boolean verdict"))
      (is (true? (facts/required-evidence-satisfied?
                  j
                  (set (mapcat (comp :evidence val)
                               (get-in facts/catalog [j :requirements])))))
          (str j " must be satisfiable when every listed evidence key is present")))))

(deftest string-jurisdictions-resolve-to-the-catalog
  ;; Subject records carry :jurisdiction as a STRING while catalog is keyed by
  ;; keyword. Until 2026-07-25 nothing bridged the two, so the Governor's only
  ;; catalog lookup missed for EVERY real subject and the evidence gate was
  ;; dead code.
  (is (= :JPN (facts/normalize-jurisdiction "JPN")))
  (is (= :JPN (facts/normalize-jurisdiction :JPN)))
  (is (nil? (facts/normalize-jurisdiction 42)) "unusable values fail closed")
  (is (nil? (facts/normalize-jurisdiction nil)))

  (testing "the string form now sees the same requirements as the keyword form"
    (is (= (facts/requirement-citations :JPN)
           (facts/requirement-citations "JPN")))
    (is (seq (facts/requirement-citations "JPN"))
        "a string jurisdiction used to resolve to nil -- that was the dead gate")))

(deftest evidence-gate-actually-bites-for-string-jurisdictions
  (let [required (set (mapcat (comp :evidence val)
                              (facts/requirement-citations "JPN")))]
    (is (seq required) "there must be real requirements to satisfy")
    (is (true? (facts/required-evidence-satisfied? "JPN" required))
        "a complete checklist passes")
    (is (false? (facts/required-evidence-satisfied? "JPN" #{}))
        "an EMPTY checklist must now fail -- it silently passed before")
    (doseq [k required]
      (is (false? (facts/required-evidence-satisfied? "JPN" (disj required k)))
          (str "dropping " k " must fail the gate")))))
