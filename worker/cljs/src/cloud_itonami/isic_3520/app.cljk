(ns cloud-itonami.isic-3520.app
  "etzhayyim-project-open-gas worker appview frontend shell.

  Faithful ClojureScript replacement of the former Svelte scaffold
  (`worker/svelte/src/routes/+page.svelte` -- a single static info card:
  title, project name, kind, route count, XRPC-enabled flag, lists of
  public routes and runtime bindings, and a source path). This does not add
  functionality the Svelte scaffold did not have; it is the same static
  shell, now built on this workspace's standard stack (reagent + re-frame +
  jp-go-dds) instead of SvelteKit. Shape follows
  orgs/cloud-itonami/port/appview/port-infra-p0rt7890/cljs/src/cloud_itonami/port/app.cljs."
  (:require [reagent.dom :as rdom]
            [re-frame.core :as rf]
            [jp-go-dds.core :as dds]))

;; -- app-db ----------------------------------------------------------------
;; Same fields, same values as the former +page.svelte `app` object.

(def default-db
  {:title "Worker"
   :project "etzhayyim-project-open-gas"
   :name "worker"
   :kind "worker"
   :route-count 0
   :routes []
   :vars []
   :xrpc? true
   :relative-path "60-apps/etzhayyim-project-open-gas/worker/svelte/src/routes/+page.svelte"})

;; -- events ------------------------------------------------------------------

(rf/reg-event-db
 :init-db
 (fn [_ _] default-db))

;; -- subs --------------------------------------------------------------------

(rf/reg-sub :app/title (fn [db _] (:title db)))
(rf/reg-sub :app/project (fn [db _] (:project db)))
(rf/reg-sub :app/name (fn [db _] (:name db)))
(rf/reg-sub :app/kind (fn [db _] (:kind db)))
(rf/reg-sub :app/route-count (fn [db _] (:route-count db)))
(rf/reg-sub :app/routes (fn [db _] (:routes db)))
(rf/reg-sub :app/vars (fn [db _] (:vars db)))
(rf/reg-sub :app/xrpc? (fn [db _] (:xrpc? db)))
(rf/reg-sub :app/relative-path (fn [db _] (:relative-path db)))

;; -- view ----------------------------------------------------------------

(defn- fact-card [label value]
  [dds/card
   [:p label]
   [:strong value]])

(defn- facts []
  [dds/row
   (fact-card "Project" @(rf/subscribe [:app/project]))
   (fact-card "Routes" @(rf/subscribe [:app/route-count]))
   (fact-card "XRPC" (if @(rf/subscribe [:app/xrpc?]) "enabled" "not configured"))])

(defn- public-routes []
  (let [routes @(rf/subscribe [:app/routes])]
    [dds/section {:title "Public Routes"}
     (if (seq routes)
       [:ul (for [r routes] ^{:key r} [:li r])]
       [:p "No public route is declared next to this app surface."])]))

(defn- runtime-bindings []
  (let [vars @(rf/subscribe [:app/vars])]
    [dds/section {:title "Runtime Bindings"}
     (if (seq vars)
       [dds/row (for [k vars] ^{:key k} [dds/chip-label k])]
       [:p "No public vars are declared in the nearest wrangler config."])]))

(defn- source []
  [dds/section {:title "Source"}
   [:p @(rf/subscribe [:app/relative-path])]])

(defn view []
  (let [title @(rf/subscribe [:app/title])
        kind @(rf/subscribe [:app/kind])
        name @(rf/subscribe [:app/name])]
    [dds/container
     [:div {:class "dds-ext-hero"}
      [:p (str "Cloudflare " kind)]
      [dds/heading 1 title]
      [:span name]]
     [facts]
     [public-routes]
     [runtime-bindings]
     [source]]))

;; -- mount ---------------------------------------------------------------

(defn main []
  (rf/dispatch-sync [:init-db])
  (rdom/render [view] (.getElementById js/document "app")))
