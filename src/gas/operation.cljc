(ns gas.operation
  "OperationActor -- one gas supply coordination run,
  expressed as a genuinely compiled langgraph-clj StateGraph binding the
  Gas Supply Advisor, the Gas Safety Governor, and the audit
  ledger.

  One graph run = one operation (intake -> advise -> govern -> decide ->
  commit | hold | request-approval -> commit). No unbounded inner loop --
  each run is auditable and checkpointed.

  Human-in-the-loop = real approval workflow: `interrupt-before
  #{:request-approval}` pauses the actor and hands the decision to a
  human operator. The approver resumes with
  `{:approval {:status :approved}}` (or `:rejected`).

  `:actuation/provision-supply`/`:actuation/suspend-supply` are NEVER in
  any phase's `:auto` set (gas.phase) and the governor's confidence-gate
  check independently forces them to escalate -- two layers agree
  actuation is always human-approved."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [gas.advisor :as advisor]
            [gas.governor :as governor]
            [gas.phase :as phase]
            [gas.store :as store]))

(defn- proposal-for
  "The Advisor proposes based on `op` -- each op has its own advisor
  method (gas.advisor's protocol shape), not a single generic
  `-advise`."
  [adv {:keys [op subject jurisdiction reason]}]
  (case op
    :customer/intake            (advisor/intake adv subject jurisdiction)
    :meter/verify                (advisor/verify-meter adv subject jurisdiction)
    :actuation/provision-supply  (advisor/provision-proposal adv subject)
    :actuation/suspend-supply    (advisor/suspension-proposal adv subject reason)))

(defn- commit-fact [request proposal]
  {:t           :committed
   :op          (:op request)
   :subject     (:subject request)
   :disposition :commit
   :basis       (:cites proposal)})

(defn build
  "Compiles an OperationActor graph bound to `store` (any `gas.store/Store`).
  opts:
    :advisor      -- a `gas.advisor/Advisor` (default: mock-advisor)
    :phase-num    -- rollout phase (default: 3)
    :checkpointer -- langgraph checkpointer (default: in-mem)"
  [store & [{:keys [advisor phase-num checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    phase-num    3
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :proposal    {:default nil}
         :evaluation  {:default nil}
         :disposition {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; Gas Supply Advisor inference (the contained intelligence node)
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (proposal-for advisor request)]
            {:proposal p})))

      ;; Gas Safety Governor -- independent censor (separate system from LLM)
      (g/add-node :govern
        (fn [{:keys [proposal]}]
          {:evaluation (governor/evaluate proposal store)}))

      ;; Decide: governor evaluation, then phase gate (which can only add caution)
      (g/add-node :decide
        (fn [{:keys [request evaluation]}]
          (cond
            (:holds? evaluation)
            {:disposition :hold
             :audit [{:t :governor-hold :op (:op request) :subject (:subject request)
                      :violations (:hard-violations evaluation)}]}

            (seq (:soft-violations evaluation))
            {:disposition :escalate
             :audit [{:t :approval-requested :op (:op request) :subject (:subject request)
                      :reason (:soft-violations evaluation)}]}

            (phase/can-auto-commit? phase-num (:op request))
            {:disposition :commit}

            :else
            {:disposition :escalate
             :audit [{:t :approval-requested :op (:op request) :subject (:subject request)
                      :reason :phase-requires-approval}]})))

      ;; Request human approval (holds here until external resume)
      (g/add-node :request-approval
        (fn [{:keys [request audit]}]
          {:audit (conj audit {:t :approval-requested-operator :op (:op request)
                                :subject (:subject request)})}))

      ;; Terminal node (commit) -- every commit lands in the store's
      ;; append-only ledger AND, for actuation ops, the SSoT record.
      (g/add-node :commit
        (fn [{:keys [request proposal]}]
          (when (contains? #{:actuation/provision-supply :actuation/suspend-supply} (:op request))
            (store/commit-record! store {:op (:op request) :subject (:subject request)}))
          (let [fact (commit-fact request proposal)]
            (store/append-ledger! store fact)
            {:disposition :commit :audit [fact]})))

      ;; Terminal node (hold)
      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(= :governor-hold (:t %)) audit))]
            (store/append-ledger! store hf))
          {}))

      ;; Edges: standard flow
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      ;; From decide: hold | escalate (-> approval) | commit
      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :hold     :hold
            :escalate :request-approval
            :commit   :commit))
        {:hold :hold :request-approval :request-approval :commit :commit})

      ;; Approval resumed externally
      (g/add-conditional-edges :request-approval
        (fn [{:keys [approval]}]
          (if (= :approved (:status approval)) :commit :hold))
        {:commit :commit :hold :hold})

      (g/set-entry-point :intake)
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph {:checkpointer checkpointer
                         :interrupt-before #{:request-approval}})))
