(ns gas.operation
  "OperationActor: langgraph-clj StateGraph binding the Gas Supply Advisor,
  Gas Safety Governor, and audit ledger."
  (:require [gas.advisor :as advisor]
            [gas.governor :as governor]
            [gas.store :as store]))

;; ----------------------------- operation state schema -----------------------------

(def init-state
  "Initial operation state."
  {:phase 0
   :proposal nil
   :evaluation nil
   :held? false
   :hold-reason nil
   :decision nil
   :record nil})

;; ----------------------------- state nodes -----------------------------

(defn node-intake
  "Customer intake phase."
  [state]
  (let [{:keys [customer-id jurisdiction store advisor]} state]
    (advisor/intake advisor customer-id jurisdiction)
    (update state :phase (fn [p] (min 1 (+ p 1))))))

(defn node-verify
  "Meter verification phase."
  [state]
  (let [{:keys [customer-id store advisor]} state]
    (when-let [customer (store/customer store customer-id)]
      (swap! (:store-atom state) store/append-ledger!
        {:op :meter/verify :subject customer-id :timestamp (js/Date.)}))
    (update state :phase (fn [p] (min 2 (+ p 1))))))

(defn node-propose
  "Advisor proposes an operation."
  [state]
  (let [{:keys [customer-id advisor operation-type]} state
        proposal (case operation-type
                   :provision (advisor/provision-proposal advisor customer-id)
                   :suspension (advisor/suspension-proposal advisor customer-id "payment")
                   nil)]
    (assoc state :proposal proposal)))

(defn node-evaluate
  "Governor evaluates the proposal."
  [state]
  (let [{:keys [proposal store]} state
        evaluation (governor/evaluate proposal store)]
    (assoc state :evaluation evaluation
                 :held? (:holds? evaluation))))

(defn node-hold
  "Hold on HARD violations."
  [state]
  (let [{:keys [evaluation]} state]
    (assoc state :decision :hold
                 :hold-reason (:hard-violations evaluation))))

(defn node-escalate
  "Escalate to human for soft violations or actuation."
  [state]
  (assoc state :decision :escalate-to-human))

(defn node-commit
  "Commit a clean proposal to the store."
  [state]
  (let [{:keys [proposal store]} state]
    (store/commit-record! store proposal)
    (store/append-ledger! store
      {:op (:op proposal) :subject (:subject proposal) :timestamp (js/Date.)})))

;; ----------------------------- conditional edges (routing) -----------------------------

(defn route-after-evaluate
  "Route based on governor evaluation."
  [state]
  (let [{:keys [evaluation]} state]
    (cond
      (:holds? evaluation) :hold
      (seq (:soft-violations evaluation)) :escalate
      :else :commit)))

;; ----------------------------- test helpers -----------------------------

(defn run-operation
  "Run a single operation through the StateGraph (demo/test driver)."
  [store advisor customer-id operation-type]
  (let [initial (merge init-state
                  {:customer-id customer-id
                   :store store
                   :advisor advisor
                   :operation-type operation-type
                   :store-atom (atom store)})]
    (-> initial
      (node-propose)
      (node-evaluate)
      (route-after-evaluate))))
