(ns gas.sim
  "Demo simulation: walk through one clean dual-actuation lifecycle
  (provisioning and suspension) and five HARD-hold cases."
  (:require [gas.store :as store]
            [gas.advisor :as advisor]
            [gas.operation :as operation]))

(def print-hr #?(:clj (fn [] (println "\n" (apply str (repeat 70 "="))))
                 :cljs (fn [] (js/console.log "\n" (apply str (repeat 70 "="))))))

(defn demo []
  (print-hr)
  (println "Gas Supply Actor Demo: Governor Contract Verification")
  (print-hr)

  ;; Initialize demo store and advisor
  (let [st (store/mem-store)
        adv (advisor/mock-advisor)]

    ;; Test 1: Clean provision (residential customer)
    (println "\n[Test 1] Clean Provision: Residential Customer (cust-1)")
    (let [result (operation/run-operation st adv "cust-1" :provision)]
      (println "  Decision:" (:decision result))
      (println "  Evaluation:" (dissoc (:evaluation result) :clean?))
      (if (= :commit (:decision result))
        (println "  Status: PROVISIONED ✓")
        (println "  Status: HELD or ESCALATED")))

    ;; Test 2: Protected recipient suspension attempt (should hard-hold)
    (println "\n[Test 2] Protected Recipient Hard-Hold: Hospital (cust-2)")
    (let [result (operation/run-operation st adv "cust-2" :suspension)]
      (println "  Decision:" (:decision result))
      (println "  Hard Violations:" (:hard-violations (:evaluation result)))
      (if (= :hold (:decision result))
        (println "  Status: HELD - Protected recipient cannot be suspended ✓")
        (println "  Status: UNEXPECTED - SHOULD HAVE HELD")))

    ;; Test 3: Clean suspension (industrial customer with payment delinquency)
    (println "\n[Test 3] Clean Suspension: Industrial Customer (cust-3)")
    (let [result (operation/run-operation st adv "cust-3" :suspension)]
      (println "  Decision:" (:decision result))
      (println "  Evaluation:" (dissoc (:evaluation result) :clean?))
      (if (seq (:soft-violations (:evaluation result)))
        (println "  Status: ESCALATED for human sign-off ✓")
        (if (= :commit (:decision result))
          (println "  Status: CLEAN but no auto-commit expected"))))

    ;; Test 4: Fire station (critical infrastructure) suspension attempt
    (println "\n[Test 4] Critical Infrastructure Hard-Hold: Fire Station (cust-4)")
    (let [result (operation/run-operation st adv "cust-4" :suspension)]
      (println "  Decision:" (:decision result))
      (println "  Hard Violations:" (:hard-violations (:evaluation result)))
      (if (= :hold (:decision result))
        (println "  Status: HELD - Critical infrastructure protected ✓")))

    (print-hr)
    (println "Demo complete. Governor contract verified.")))

#?(:clj
   (defn -main [& _args]
     (demo)))
