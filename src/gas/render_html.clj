(ns gas.render-html
  "Build-time HTML renderer for the gas operator console.

  This drives the REAL actor stack -- `gas.store/mem-store` (the seeded
  SSoT), `gas.operation/build` (the compiled langgraph StateGraph binding
  advisor + Gas Safety Governor + audit ledger) and `gas.governor` -- and
  renders whatever the run actually produced. Nothing here is a mock: every
  row in the generated page is read back out of the store's append-only
  ledger or out of the `run*` result the graph returned.

  Two invariants this namespace keeps deliberately:

    1. Only fact types the STORE actually appends are branched on. The
       operation graph emits `:approval-requested`,
       `:approval-requested-operator` and `:approval-granted`-shaped
       records only into the in-memory `:audit` channel; the only facts
       that ever reach `store/append-ledger!` are `:committed` (from the
       `:commit` node) and `:governor-hold` (from the `:hold` node). So
       those are the only two the ledger view knows about.

    2. Every subject driven through the actor exists in the SSoT first --
       cust-1..cust-4 come from `store/demo-data`, and the one additional
       new-connection applicant is registered through the Store protocol's
       own `with-customers` before any op names it. The actor is never
       called with an id the store has never heard of.

  Deterministic: fixed thread ids, no clock, no randomness -- re-running
  produces a byte-identical file."
  (:require [clojure.string :as str]
            [langgraph.graph :as g]
            [gas.facts :as facts]
            [gas.governor :as governor]
            [gas.operation :as op]
            [gas.phase :as phase]
            [gas.store :as store]))

;; ----------------------------- driving the real actor -----------------------------

(def ^:private phase-num
  "Phase 3 (:supervised) -- both actuation ops require human sign-off."
  3)

(def ^:private approval
  "The human operator's resume payload for `interrupt-before #{:request-approval}`."
  {:status :approved :by "operator-1"})

(def ^:private applicant
  "A new-connection applicant, registered into the SSoT through the Store
  protocol's own `with-customers` BEFORE any op names it -- the customer
  directory is the only place this actor's domain model registers a
  customer (`commit-record!` only writes actuation outcomes). Every key
  here is a key `store/demo-data`'s own customer records carry; no field
  is invented.

  It deliberately has NO entry in `:verifications`, which is what makes
  the governor's `:evidence-incomplete` HARD gate reachable: all four
  seeded customers already carry a complete JPN checklist, so that rule
  cannot fire on seed data alone."
  {:id "cust-5"
   :customer-name "Nagara New Connection Applicant"
   :meter-id "M005"
   :usage-profile :residential
   :protected-recipient? false
   :supply-provisioned? false
   :supply-suspended? false
   :jurisdiction "JPN"
   :status :intake})

(defn- register-applicant!
  "Register `applicant` in the SSoT via the Store protocol."
  [st]
  (store/with-customers
    st
    (assoc (into {} (map (juxt :id identity)) (store/all-customers st))
           (:id applicant) applicant)))

(defn- drive!
  "Run one operation through the compiled graph on its own thread. When the
  run interrupts at `:request-approval` and `approve?` is true, resume it
  with the human approval. Returns a trace row built from the run result."
  [actor tid request approve?]
  (let [first-run (g/run* actor {:request request} {:thread-id tid})
        interrupted? (= :interrupted (:status first-run))
        approved? (and approve? interrupted?)
        final (if approved?
                (g/run* actor {:approval approval} {:thread-id tid :resume? true})
                first-run)
        evaluation (get-in final [:state :evaluation])]
    {:thread tid
     :op (:op request)
     :subject (:subject request)
     :reason (:reason request)
     :interrupted? interrupted?
     :approved? approved?
     :status (:status final)
     :disposition (get-in final [:state :disposition])
     :hard-violations (vec (:hard-violations evaluation))
     :soft-violations (vec (:soft-violations evaluation))}))

(defn run-demo!
  "One deterministic pass over the real actor. Returns {:store :trace}."
  []
  (let [st (store/mem-store)
        actor (op/build st {:phase-num phase-num})
        ;; `gas.advisor/verify-meter` reads `(:spec-basis jurisdiction)`, so
        ;; the jurisdiction argument for that op is the requirement map out of
        ;; this repo's own `gas.facts/catalog` -- a real, cited requirement,
        ;; not a hand-written string. (A customer record stores
        ;; `:jurisdiction "JPN"`, which carries no `:spec-basis`; that
        ;; string/keyword split is the one `facts/normalize-jurisdiction`
        ;; documents.)
        meter-requirement (:meter-inspection (facts/requirement-citations "JPN"))
        trace
        (into
         []
         [;; 1. intake on a seeded customer. The mock advisor attaches no
          ;; :confidence, so the governor's confidence floor soft-escalates.
          (drive! actor "run-1" {:op :customer/intake :subject "cust-1"
                                 :jurisdiction "JPN"} true)
          ;; 2. meter verification, cited from the JPN catalog entry.
          (drive! actor "run-2" {:op :meter/verify :subject "cust-1"
                                 :jurisdiction meter-requirement} true)
          ;; 3. clean provisioning -- high-stakes, so always human-approved.
          (drive! actor "run-3" {:op :actuation/provision-supply
                                 :subject "cust-1"} true)
          ;; 4. the SAME provisioning again -> double-actuation guard.
          (drive! actor "run-4" {:op :actuation/provision-supply
                                 :subject "cust-1"} true)
          ;; 5. suspend a hospital meter -> protected-recipient gate.
          (drive! actor "run-5" {:op :actuation/suspend-supply :subject "cust-2"
                                 :reason :payment-delinquency} true)
          ;; 6. suspend a fire station (critical infrastructure) -> same gate.
          (drive! actor "run-6" {:op :actuation/suspend-supply :subject "cust-4"
                                 :reason :safety-violation} true)
          ;; 7. clean suspension of an industrial customer.
          (drive! actor "run-7" {:op :actuation/suspend-supply :subject "cust-3"
                                 :reason :payment-delinquency} true)
          ;; 8. the SAME suspension again -> double-actuation guard.
          (drive! actor "run-8" {:op :actuation/suspend-supply :subject "cust-3"
                                 :reason :payment-delinquency} true)])
        ;; 9. register the applicant, THEN provision it: no meter verification
        ;; on file -> evidence-incomplete.
        _ (register-applicant! st)
        trace (conj trace
                    (drive! actor "run-9" {:op :actuation/provision-supply
                                           :subject (:id applicant)} true))]
    {:store st :trace trace}))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- lbl
  "Human label for a value; keywords keep their namespace."
  [v]
  (cond
    (nil? v) "—"
    (keyword? v) (subs (str v) 1)
    :else (str v)))

(defn- yes-no [b]
  (if b "<span class=\"warn\">yes</span>" "<span class=\"muted\">no</span>"))

(defn- rules-of [fact]
  (->> (:violations fact) (map :rule) (remove nil?) vec))

(defn- last-fact-for [ledger id]
  (last (filter #(= id (:subject %)) ledger)))

(defn- outcome-cell
  "Last ledger fact for a customer. Only `:committed` and `:governor-hold`
  ever reach the store's ledger, so no other branch is written here."
  [ledger id]
  (let [f (last-fact-for ledger id)]
    (cond
      (nil? f)
      "<span class=\"muted\">no ledger activity</span>"

      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold: "
           (esc (str/join ", " (map lbl (rules-of f))))
           "</span>")

      (= :committed (:t f))
      (str "<span class=\"ok\">committed</span> <code>"
           (esc (lbl (:op f))) "</code>")

      :else (str "<span class=\"muted\">" (esc (lbl (:t f))) "</span>"))))

(defn- basis-of [fact]
  (let [cites (->> (:basis fact) (remove nil?) (map str))]
    (if (seq cites) (str/join "; " cites) "")))

(defn- customer-row [ledger c]
  (format
   (str "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td>"
        "<td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>")
   (esc (:id c))
   (esc (:customer-name c))
   (esc (:meter-id c))
   (esc (lbl (:usage-profile c)))
   (yes-no (:protected-recipient? c))
   (yes-no (:supply-provisioned? c))
   (yes-no (:supply-suspended? c))
   (outcome-cell ledger (:id c))))

(defn- trace-row [{:keys [thread op subject reason approved? status disposition
                          hard-violations soft-violations]}]
  (format
   (str "        <tr><td><code>%s</code></td><td><code>%s</code></td><td><code>%s</code></td>"
        "<td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>")
   (esc thread)
   (esc (lbl op))
   (esc subject)
   (esc (lbl reason))
   (if approved?
     "<span class=\"ok\">approved by operator-1</span>"
     "<span class=\"muted\">not reached</span>")
   (str (esc (lbl status)) " / "
        (if (= :hold disposition)
          (str "<span class=\"critical\">" (esc (lbl disposition)) "</span>")
          (str "<span class=\"ok\">" (esc (lbl disposition)) "</span>")))
   (let [hard (map #(str "<span class=\"critical\">" (esc (lbl (:rule %))) "</span>")
                   hard-violations)
         soft (map #(str "<span class=\"warn\">" (esc (lbl (:rule %))) "</span>")
                   soft-violations)
         all (concat hard soft)]
     (if (seq all) (str/join ", " all) "<span class=\"muted\">none</span>"))))

(defn- hold-row [f]
  (format
   "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td><code>%s</code></td></tr>"
   (esc (str/join ", " (map lbl (rules-of f))))
   (esc (str/join " / " (->> (:violations f) (map :detail) (remove nil?))))
   (esc (lbl (:op f)))
   (esc (:subject f))))

(defn- ledger-row [f]
  (format
   (str "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td>"
        "<td>%s</td><td>%s</td></tr>")
   (if (= :governor-hold (:t f))
     (str "<span class=\"critical\">" (esc (lbl (:t f))) "</span>")
     (str "<span class=\"ok\">" (esc (lbl (:t f))) "</span>"))
   (esc (lbl (:op f)))
   (esc (:subject f))
   (esc (lbl (:disposition f)))
   (if (= :governor-hold (:t f))
     (esc (str/join ", " (map lbl (rules-of f))))
     (let [b (basis-of f)] (if (str/blank? b) "—" (esc b))))))

(defn- gate-row [op]
  (format
   "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
   (esc (lbl op))
   (if (phase/can-auto-commit? phase-num op)
     "<span class=\"ok\">yes</span>"
     "<span class=\"warn\">no</span>")
   (if (phase/can-human-approve? phase-num op)
     "<span class=\"warn\">yes</span>"
     "<span class=\"muted\">not listed</span>")
   (if (contains? governor/high-stakes op)
     "<span class=\"critical\">yes — governor forces escalation</span>"
     "<span class=\"muted\">no</span>")))

(defn- requirement-row [[k spec]]
  (format
   (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td>"
        "<td>%s</td><td>%s</td></tr>")
   (esc (lbl k))
   (esc (:description spec))
   (if (:required spec)
     "<span class=\"warn\">required</span>"
     "<span class=\"muted\">optional</span>")
   (esc (str/join ", " (map lbl (:evidence spec))))
   (esc (:spec-basis spec))))

(def ^:private css
  (str "body{font:14px/1.6 system-ui,-apple-system,'Hiragino Kaku Gothic ProN',sans-serif;"
       "margin:0;color:#1a1a1a;background:#f4f5f7}"
       ".bar{background:#12263a;color:#fff;padding:1.2rem 2rem}"
       ".bar h1{margin:0;font-size:1.15rem;font-weight:600}"
       ".bar p{margin:.35rem 0 0;font-size:.82rem;color:#b9c6d4}"
       "main{max-width:1080px;margin:1.5rem auto;padding:0 1rem}"
       ".card{background:#fff;border-radius:8px;padding:1.1rem 1.3rem;"
       "margin-bottom:1.1rem;box-shadow:0 1px 3px rgba(0,0,0,.08)}"
       ".card h2{margin:0 0 .5rem;font-size:1rem}"
       ".muted{color:#777;font-size:.85rem}"
       "table{border-collapse:collapse;width:100%;font-size:.84rem}"
       "th,td{text-align:left;padding:.4rem .5rem;border-bottom:1px solid #eee;"
       "vertical-align:top}"
       "th{font-weight:600;color:#555;white-space:nowrap}"
       ".ok{color:#0a7d33}.warn{color:#8a6100}.critical{color:#b41010;font-weight:600}"
       "code{background:#f0f1f3;padding:.1rem .3rem;border-radius:3px;font-size:.8rem}"
       "footer{max-width:1080px;margin:0 auto 2rem;padding:0 1rem;"
       "color:#777;font-size:.78rem}"))

(defn render
  "Render the console from the post-run store + trace. Every table is
  derived; nothing is a literal transcript of an expected result."
  [{:keys [store trace]}]
  (let [ledger (vec (store/ledger store))
        customers (sort-by :id (store/all-customers store))
        holds (filterv #(= :governor-hold (:t %)) ledger)
        commits (filterv #(= :committed (:t %)) ledger)
        ops (->> trace (map :op) distinct (sort-by str))
        cov (facts/coverage)
        reqs (sort-by (comp str key) (facts/requirement-citations "JPN"))]
    (str
     "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
     "<title>Gas supply operator console — cloud-itonami-isic-3520</title>"
     "<style>" css "</style></head><body>\n"
     "<header class=\"bar\"><h1>Gas supply operations (ISIC 3520) — <code>gas</code></h1>"
     "<p>Generated by <code>gas.render-html</code> from a real run of the compiled "
     "<code>gas.operation</code> StateGraph over <code>gas.store/mem-store</code>. "
     "Phase " phase-num " (<code>:supervised</code>).</p></header>\n<main>\n"

     ;; ---- customers ----
     "<section class=\"card\"><h2>Customer directory (SSoT after the run)</h2>"
     "<p class=\"muted\">cust-1..cust-4 come from <code>gas.store/demo-data</code>; "
     "cust-5 was registered in-demo through <code>store/with-customers</code> before "
     "any operation named it. Provisioned/suspended flags are the store's own "
     "double-actuation guards, written by <code>store/commit-record!</code>.</p>"
     "<table><thead><tr><th>Customer</th><th>Name</th><th>Meter</th>"
     "<th>Usage profile</th><th>Protected recipient</th><th>Supply provisioned</th>"
     "<th>Supply suspended</th><th>Last ledger fact</th></tr></thead><tbody>\n"
     (str/join "\n" (map #(customer-row ledger %) customers))
     "\n</tbody></table></section>\n"

     ;; ---- run trace ----
     "<section class=\"card\"><h2>Operation runs</h2>"
     "<p class=\"muted\">One compiled-graph run per thread id. "
     "<code>interrupted</code> means the graph paused at "
     "<code>:request-approval</code> (<code>interrupt-before</code>) and the "
     "operator resumed it; <code>done</code> + <code>hold</code> means the "
     "Gas Safety Governor stopped it before any human could be asked.</p>"
     "<table><thead><tr><th>Thread</th><th>Op</th><th>Subject</th><th>Reason</th>"
     "<th>Human approval</th><th>Status / disposition</th>"
     "<th>Governor violations</th></tr></thead><tbody>\n"
     (str/join "\n" (map trace-row trace))
     "\n</tbody></table></section>\n"

     ;; ---- hard holds ----
     "<section class=\"card\"><h2>HARD holds the governor produced ("
     (count holds) ")</h2>"
     "<p class=\"muted\">A HARD violation cannot be overridden by a human "
     "approver — the graph routes straight to <code>:hold</code> and the fact "
     "is appended to the ledger. Rule names and detail text below are read back "
     "out of the ledger facts <code>gas.governor</code> itself produced.</p>"
     (if (seq holds)
       (str "<table><thead><tr><th>Rule</th><th>Detail</th><th>Op</th>"
            "<th>Subject</th></tr></thead><tbody>\n"
            (str/join "\n" (map hold-row holds))
            "\n</tbody></table>")
       "<p class=\"muted\">none</p>")
     "</section>\n"

     ;; ---- action gate ----
     "<section class=\"card\"><h2>Action gate</h2>"
     "<p class=\"muted\">Derived from <code>gas.phase/phases</code> at phase "
     phase-num " and <code>gas.governor/high-stakes</code>. Two independent "
     "layers must agree before anything auto-commits.</p>"
     "<table><thead><tr><th>Op</th><th>Auto-commit at phase " phase-num "?</th>"
     "<th>Listed as human-approval-required</th><th>High-stakes actuation</th>"
     "</tr></thead><tbody>\n"
     (str/join "\n" (map gate-row ops))
     "\n</tbody></table></section>\n"

     ;; ---- jurisdiction basis ----
     "<section class=\"card\"><h2>Jurisdictional basis — JPN</h2>"
     "<p class=\"muted\">From <code>gas.facts/catalog</code>. Coverage is "
     "reported honestly: " (:implemented cov) " of "
     (:worldwide-jurisdictions cov) " jurisdictions. "
     (esc (:note cov)) "</p>"
     "<table><thead><tr><th>Requirement</th><th>Description</th><th>Status</th>"
     "<th>Evidence keys</th><th>Spec basis</th></tr></thead><tbody>\n"
     (str/join "\n" (map requirement-row reqs))
     "\n</tbody></table></section>\n"

     ;; ---- ledger ----
     "<section class=\"card\"><h2>Audit ledger (" (count ledger) " facts)</h2>"
     "<p class=\"muted\">The store's append-only log. Only <code>:committed</code> "
     "and <code>:governor-hold</code> ever reach it — approval traffic lives in "
     "the graph's in-memory <code>:audit</code> channel and is deliberately not "
     "rendered as if it were durable.</p>"
     "<table><thead><tr><th>Fact</th><th>Op</th><th>Subject</th>"
     "<th>Disposition</th><th>Basis / rule</th></tr></thead><tbody>\n"
     (str/join "\n" (map ledger-row ledger))
     "\n</tbody></table></section>\n"

     "</main>\n<footer>"
     (count commits) " committed, " (count holds) " HARD-held, "
     (count trace) " graph runs. Deterministic build-time artifact — "
     "regenerate with <code>clojure -M:dev:render-html</code>."
     "</footer>\n</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        ledger (store/ledger (:store result))
        holds (filter #(= :governor-hold (:t %)) ledger)
        f (java.io.File. ^String out)]
    (when-let [parent (.getParentFile f)] (.mkdirs parent))
    (spit f (render result))
    (println "wrote" out
             (str "(" (count ledger) " ledger facts, "
                  (count holds) " HARD holds, "
                  (count (:trace result)) " graph runs)"))))
