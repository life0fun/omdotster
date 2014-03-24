(ns dots.app
  (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]]
                   [secretary.macros :refer [defroute]])
  (:require [goog.events :as events]
            [cljs.core.async :refer [put! <! chan]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [secretary.core :as secretary]
            [dots.utils :refer [pluralize now guid store hidden]]
            [clojure.string :as string]
            [dots.item :as item])
  (:import [goog History]
           [goog.history EventType]))

(enable-console-print!)

(def ENTER_KEY 13)

; when starting, screen is newgame, 
; :board is a vec of vec, each dot pos is a tuple has :color and :ele of html
(def app-state (atom {:screen :newgame :board nil :dot-chain [] :draw-chan nil}))

;; =============================================================================
;; Routing

(defroute "/" [] (swap! app-state assoc :dot-chain []))

(defroute "/:filter" [filter] (swap! app-state assoc :showing (keyword filter)))

(def history (History.))

(events/listen history EventType.NAVIGATE
  (fn [e] (secretary/dispatch! (.-token e))))

(.setEnabled history true)

;; =============================================================================
;; Main and Footer Components

(declare toggle-all)

; create start screen with new game button
; pass in app state and comm chan so to send back evt to app
(defn start-screen
  [app comm]
  (dom/div #js {:className "dots-game"}
    (dom/div #js {:className "notice-square"}
      (dom/div #js {:className "control-area"}
        (dom/button #js {:id "new-game" 
                         :onClick #(put! comm [:newgame (now)])}
                    (str "New Game"))))))

(defn visible? [todo filter]
  (case filter
    :all true
    :active (not (:completed todo))
    :completed (:completed todo)))


(defn main [{:keys [dot-chain board] :as app} comm]
  (dom/section #js {:id "main" :style (hidden (empty? todos))}
    (dom/header #js {:id "header"}
      (dom/div #js {:className "header"} (str "Time"))
      (dom/div #js {:className "header"} (str "Score")))
    (dom/div #js {:className "board-area"}
      (dom/div #js {:className "chain-line"})
      (dom/div #js {:className "board"})
      (make-dots-div app comm))))


(defn make-dots-div
  [app comm]
  (apply dom/div #js {:className (str "dot levelish " color " level-" level)}
    (om/build-all dots/dot (:board app)
      {:init-state {:comm comm}})))

;; =============================================================================

(defn toggle-all [e app]
  (let [checked (.. e -target -checked)]
    (om/transact! app :todos
      (fn [todos] (vec (map #(assoc % :completed checked) todos))))))

(defn handle-new-todo-keydown [e app owner]
  (when (== (.-which e) ENTER_KEY)
    (let [new-field (om/get-node owner "newField")]
      (when-not (string/blank? (.. new-field -value trim))
        (let [new-todo {:id (guid)
                        :title (.-value new-field)
                        :completed false}]
          (om/transact! app :todos
            #(conj % new-todo)
            [:create new-todo]))
        (set! (.-value new-field) "")))
    false))

(defn destroy-todo [app {:keys [id]}]
  (om/transact! app :todos
    (fn [todos] (into [] (remove #(= (:id %) id) todos)))
    [:delete id]))

(defn edit-todo [app {:keys [id]}] (om/update! app :editing id))

(defn save-todos [app] (om/update! app :editing nil))

(defn cancel-action [app] (om/update! app :editing nil))

(defn clear-completed [app]
  (om/transact! app :todos
    (fn [todos] (into [] (remove :completed todos)))))

(defn handle-event [type app val]
  (case type
    :destroy (destroy-todo app val)
    :edit    (edit-todo app val)
    :save    (save-todos app)
    :clear   (clear-completed app)
    :cancel  (cancel-action app)
    nil))

(def render-start nil)

(defn dots-app [{:keys [dots screen] :as app} owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (let [comm (chan)]
        (om/set-state! owner :comm comm)
        (go (while true
              (let [[type value] (<! comm)]
                (handle-event type app value))))))
    om/IWillUpdate
    (will-update [_ _ _] (set! render-start (now)))
    om/IDidUpdate
    (did-update [_ _ _]
      (store "todos" todos)
      (let [ms (- (.valueOf (now)) (.valueOf render-start))]
        (set! (.-innerHTML (js/document.getElementById "message")) (str ms "ms"))))
    om/IRenderState
    (render-state [_ {:keys [comm]}]
      (if (= screen :newgame)
        (start-screen app comm)
        (main app comm))))


(om/root dots-app app-state
  {:target (.getElementByClass js/document "dots-game-container")})

(dom/render
  (dom/div nil
    (dom/p nil "Dots Game")
    (dom/p nil
      (dom/a #js {:href "http://github.com/dotster"}))
    (dom/p nil
      #js ["Part of"
           (dom/a #js {:href "http://dots.com"} "dots")]))
  (.getElementById js/document "info"))

;; =============================================================================
;; Benchmark Stuff

(aset js/window "benchmark1"
  (fn [e]
    (dotimes [_ 200]
      (swap! app-state update-in [:todos] conj
        {:id (guid) :title "foo" :completed false}))))

(aset js/window "benchmark2"
  (fn [e]
    (dotimes [_ 200]
      (swap! app-state update-in [:todos] conj
        {:id (guid) :title "foo" :completed false}))
    (dotimes [_ 5]
      (swap! app-state update-in [:todos]
        (fn [todos]
          (map #(assoc-in % [:completed] not) todos))))
    (swap! app-state update-in [:todos]
      (fn [todos]
        (into [] (remove :completed todos))))))
