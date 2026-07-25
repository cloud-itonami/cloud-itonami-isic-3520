(ns gas.store-contract-test
  (:require [clojure.test :refer [deftest is]]
            [gas.store :as store]))

(deftest ^{:doc "Store should retrieve customers correctly."} mem-store-retrieval
  (let [st (store/mem-store)]
    (is (store/customer st "cust-1")
      "Should retrieve a known customer")
    (is (not (store/customer st "unknown"))
      "Should return nil for unknown customer")
    (is (pos? (count (store/all-customers st)))
      "Should have demo customers")))

(deftest ^{:doc "Ledger should append facts immutably."} ledger-append
  (let [st (store/mem-store)
        fact1 {:op :customer/intake :subject "cust-1"}
        fact2 {:op :meter/verify :subject "cust-1"}]
    (store/append-ledger! st fact1)
    (store/append-ledger! st fact2)
    (let [ledger (store/ledger st)]
      (is (= 2 (count ledger)) "Ledger should have both facts")
      (is (= fact1 (first ledger)) "First fact should be preserved")
      (is (= fact2 (second ledger)) "Second fact should be second"))))

(deftest ^{:doc "Store should track provision status."} provision-guard
  (let [st (store/mem-store)]
    (is (not (store/customer-already-provisioned? st "cust-1"))
      "Initially, customer should not be provisioned")
    ;; Mark as provisioned
    (swap! (-> st :data) assoc-in [:customers "cust-1" :supply-provisioned?] true)
    (is (store/customer-already-provisioned? st "cust-1")
      "After marking, should show as provisioned")))

(deftest ^{:doc "Store should track suspension status."} suspension-guard
  (let [st (store/mem-store)]
    (is (not (store/customer-already-suspended? st "cust-1"))
      "Initially, customer should not be suspended")
    ;; Mark as suspended
    (swap! (-> st :data) assoc-in [:customers "cust-1" :supply-suspended?] true)
    (is (store/customer-already-suspended? st "cust-1")
      "After marking, should show as suspended")))

(deftest ^{:doc "Store should increment sequence numbers per jurisdiction."} sequence-counters
  (let [st (store/mem-store)]
    (is (= 1 (store/next-provision-sequence st "JPN"))
      "First sequence should be 1")
    ;; Increment counter
    (swap! (-> st :data) update-in [:provision-counters "JPN"] (fnil inc 0))
    (is (= 2 (store/next-provision-sequence st "JPN"))
      "After increment, next sequence should be 2")
    ;; Different jurisdiction should start at 1
    (is (= 1 (store/next-suspension-sequence st "USA"))
      "Different jurisdiction should have separate counter")))
