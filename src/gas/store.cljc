(ns gas.store
  "SSoT for the gas actor, behind a `Store` protocol so the backend
  is a swap, not a rewrite -- the same seam every cloud-itonami-isic-*
  actor uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api`.

  Both implement the same protocol and pass the same contract
  (test/gas/store_contract_test.clj), which is the whole point: the
  actor, the Gas Safety Governor and the audit ledger never know
  which SSoT they run on.

  Like every dual-actuation sibling, this actor has TWO actuation
  events (provisioning and suspending supply) acting on the SAME entity
  (a customer meter), each with its OWN history collection, sequence
  counter and dedicated double-actuation-guard boolean
  (`:supply-provisioned?`/`:supply-suspended?`, never a `:status` value)
  -- the same discipline every prior sibling governor's guards establish.

  The ledger stays append-only on every backend: 'which customer was
  verified, which supply was provisioned, which was suspended, on what
  jurisdictional basis, approved by whom' is always a query over an
  immutable log -- the audit trail a community trusting a gas operator
  needs."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [gas.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (customer [s id])
  (all-customers [s])
  (meter-verification-of [s customer-id] "committed meter verification, or nil")
  (ledger [s])
  (provision-history [s] "the append-only supply-provision history (gas.registry drafts)")
  (suspension-history [s] "the append-only suspension history (gas.registry drafts)")
  (next-provision-sequence [s jurisdiction] "next provision-number sequence for a jurisdiction")
  (next-suspension-sequence [s jurisdiction] "next suspension-number sequence for a jurisdiction")
  (customer-already-provisioned? [s customer-id] "has this customer's supply already been provisioned?")
  (customer-already-suspended? [s customer-id] "has this customer's supply already been suspended?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-customers [s customers] "replace/seed the customer directory (map id->customer)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained customer set covering both actuation lifecycles
  (provisioning and suspending supply) so the actor + tests run offline."
  []
  {:customers
   {"cust-1" {:id "cust-1" :customer-name "Sakura Residential"
              :meter-id "M001" :usage-profile :residential
              :protected-recipient? false
              :supply-provisioned? false :supply-suspended? false
              :jurisdiction "JPN" :status :intake}
    "cust-2" {:id "cust-2" :customer-name "Hospital Central"
              :meter-id "M002" :usage-profile :hospital
              :protected-recipient? true
              :supply-provisioned? false :supply-suspended? false
              :jurisdiction "JPN" :status :intake}
    "cust-3" {:id "cust-3" :customer-name "Industrial Co."
              :meter-id "M003" :usage-profile :industrial
              :protected-recipient? false
              :supply-provisioned? false :supply-suspended? false
              :jurisdiction "JPN" :status :intake}
    "cust-4" {:id "cust-4" :customer-name "Fire Station"
              :meter-id "M004" :usage-profile :critical-infrastructure
              :protected-recipient? true
              :supply-provisioned? false :supply-suspended? false
              :jurisdiction "JPN" :status :intake}}
   :ledger []
   :provision-history []
   :suspension-history []
   :provision-counters {}
   :suspension-counters {}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [data]
  Store
  (customer [s id]
    (get-in @data [:customers id]))
  (all-customers [s]
    (vals (get @data :customers {})))
  (meter-verification-of [s customer-id]
    (get-in @data [:verifications customer-id]))
  (ledger [s]
    (get @data :ledger []))
  (provision-history [s]
    (get @data :provision-history []))
  (suspension-history [s]
    (get @data :suspension-history []))
  (next-provision-sequence [s jurisdiction]
    (let [counter-key [:provision-counters jurisdiction]]
      (inc (get-in @data counter-key 0))))
  (next-suspension-sequence [s jurisdiction]
    (let [counter-key [:suspension-counters jurisdiction]]
      (inc (get-in @data counter-key 0))))
  (customer-already-provisioned? [s customer-id]
    (get-in @data [:customers customer-id :supply-provisioned?] false))
  (customer-already-suspended? [s customer-id]
    (get-in @data [:customers customer-id :supply-suspended?] false))
  (commit-record! [s record]
    (let [customer-id (:subject record)
          op (:op record)]
      (swap! data
        (fn [d]
          (cond
            (= op :actuation/provision-supply)
            (-> d
              (assoc-in [:customers customer-id :supply-provisioned?] true)
              (update :provision-counters
                (fn [c] (update c (get-in d [:customers customer-id :jurisdiction])
                                (fnil inc 0)))))
            (= op :actuation/suspend-supply)
            (-> d
              (assoc-in [:customers customer-id :supply-suspended?] true)
              (update :suspension-counters
                (fn [c] (update c (get-in d [:customers customer-id :jurisdiction])
                                (fnil inc 0)))))
            :else d)))))
  (append-ledger! [s fact]
    (swap! data update :ledger conj fact))
  (with-customers [s customers]
    (swap! data assoc :customers customers)))

(defn mem-store
  "Create a demo-seeded MemStore for offline dev/test."
  []
  (MemStore. (atom (demo-data))))
