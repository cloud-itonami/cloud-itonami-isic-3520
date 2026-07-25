(ns gas.phase-test
  (:require [clojure.test :refer [deftest is]]
            [gas.phase :as phase]))

(deftest ^{:doc "CRITICAL: Neither :actuation/provision-supply nor :actuation/suspend-supply
  are ever in any phase's :auto set. All actuation requires human sign-off."} actuation-never-auto-at-any-phase
  (is (phase/actuation-never-auto?)
    "Actuation operations must never be in any phase's auto-commit set"))

(deftest ^{:doc "Phase 0 is read-only: no auto-commits."} phase-0-read-only
  (is (not (phase/can-auto-commit? 0 :customer/intake))
    "Intake should not auto at phase 0")
  (is (not (phase/can-auto-commit? 0 :meter/verify))
    "Verify should not auto at phase 0"))

(deftest ^{:doc "Phase 1 allows customer intake auto-commit."} phase-1-intake
  (is (phase/can-auto-commit? 1 :customer/intake)
    "Intake should auto at phase 1")
  (is (phase/can-human-approve? 1 :meter/verify)
    "Verify requires human approval at phase 1"))

(deftest ^{:doc "Phase 2 allows meter verification auto-commit."} phase-2-verification
  (is (phase/can-auto-commit? 2 :meter/verify)
    "Verify should auto at phase 2")
  (is (phase/can-human-approve? 2 :actuation/provision-supply)
    "Provision requires human approval at phase 2"))

(deftest ^{:doc "Phase 3: all actuation requires human sign-off, no auto-commits."} phase-3-supervised
  (is (not (phase/can-auto-commit? 3 :customer/intake))
    "No intake auto at phase 3")
  (is (not (phase/can-auto-commit? 3 :meter/verify))
    "No verify auto at phase 3")
  (is (not (phase/can-auto-commit? 3 :actuation/provision-supply))
    "Provision never auto")
  (is (not (phase/can-auto-commit? 3 :actuation/suspend-supply))
    "Suspension never auto")
  (is (phase/can-human-approve? 3 :actuation/provision-supply)
    "Provision can be human-approved at phase 3")
  (is (phase/can-human-approve? 3 :actuation/suspend-supply)
    "Suspension can be human-approved at phase 3"))
