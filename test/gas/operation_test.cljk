(ns gas.operation-test
  (:require [clojure.test :refer [deftest testing is]]
            [langgraph.graph :as g]
            [gas.operation :as operation]
            [gas.store :as store]))

(defn- run-op!
  "Runs the compiled graph once; returns the full run* result
  {:state :events :status :frontier} so tests can assert on both the
  interrupt/done status and the final disposition."
  [store request & [opts]]
  (g/run* (operation/build store opts) {:request request} {:thread-id (str (random-uuid))}))

(deftest ledger-empty-until-run
  (testing "A fresh store's ledger is empty before any operation runs"
    (let [s (store/mem-store)]
      (is (empty? (store/ledger s))))))

(deftest verify-meter-auto-commits-at-phase-2
  (testing ":meter/verify carries a genuine confidence (0.85, above the 0.6 floor) and is
  not in the actuation high-stakes set -- at phase-2, where it's in :auto, it cleanly commits"
    (let [s (store/mem-store)
          result (run-op! s {:op :meter/verify :subject "cust-1" :jurisdiction "JPN"}
                           {:phase-num 2})]
      (is (= :done (:status result)))
      (is (= :commit (get-in result [:state :disposition])))
      (is (= 1 (count (store/ledger s)))))))

(deftest intake-never-auto-commits-because-advisor-attaches-no-confidence
  (testing "customer/intake's mock-advisor proposal carries no :value/:confidence at all,
  so the governor's confidence-gate always defaults it below the 0.6 floor and soft-escalates
  -- true at every phase, including phase-1 where the phase table nominally allows :auto.
  Two independent layers (phase table + governor) must BOTH agree before auto-commit; here
  the governor never does, by design of this actor's conservative confidence-floor gate."
    (let [s (store/mem-store)
          result (run-op! s {:op :customer/intake :subject "cust-1" :jurisdiction "JPN"}
                           {:phase-num 1})]
      (is (= :interrupted (:status result)))
      (is (empty? (store/ledger s)) "held at interrupt, nothing committed yet"))))

(deftest provision-supply-always-interrupts-even-when-clean
  (testing ":actuation/provision-supply is high-stakes -- always interrupts for human approval, never auto-commits, at any phase"
    (let [s (store/mem-store)
          result (run-op! s {:op :actuation/provision-supply :subject "cust-1"}
                           {:phase-num 3})]
      (is (= :interrupted (:status result)))
      (is (empty? (store/ledger s))))))

(deftest suspend-protected-recipient-holds-hard
  (testing "A protected-recipient customer (hospital) can NEVER be suspended -- HARD hold, never even reaches the approval interrupt"
    (let [s (store/mem-store)
          result (run-op! s {:op :actuation/suspend-supply :subject "cust-2" :reason :test}
                           {:phase-num 3})]
      (is (= :done (:status result)))
      (is (= :hold (get-in result [:state :disposition])))
      (is (= 1 (count (store/ledger s))))
      (is (= :governor-hold (:t (first (store/ledger s))))))))

(deftest each-clean-run-appends-exactly-one-ledger-entry
  (testing "Ledger accumulates one entry per operation run, never more"
    (let [s (store/mem-store)]
      (run-op! s {:op :meter/verify :subject "cust-1" :jurisdiction "JPN"} {:phase-num 2})
      (run-op! s {:op :meter/verify :subject "cust-2" :jurisdiction "JPN"} {:phase-num 2})
      (is (= 2 (count (store/ledger s)))))))
