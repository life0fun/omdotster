(ns dots.app
  
  (:require [goog.events :as events]
            [cljs.core.async :refer [put! <! chan]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [secretary.core :as secretary]
            [clojure.string :as string]
            [clojure.set :refer [union]]

            [jayq.core :refer [$ append ajax inner css $deferred when done 
                               resolve pipe on bind attr offset] :as jq]
            [jayq.util :refer [log]]

            [dots.utils :refer [pluralize now guid store hidden]]
            [dots.dot-chain :as dot-chain]
            [dots.board :refer [create-board start-screen render-screen score-screen  render-score
                        render-view render-position-updates render-remove-dots
                        render-dot-chain-update erase-dot-chain transition-dot-chain-state
                        dot-colors dot-color dot-index add-missing-dots
                        flash-color-on flash-color-off
                        dot-positions-for-focused-color]]
            )
  (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]]
                   [secretary.macros :refer [defroute]])
  (:import [goog History]
           [goog.history EventType]))

(enable-console-print!)

(def ENTER_KEY 13)

(declare toggle-all)

;; =============================================================================
;; Routing
(defroute "/" [] (swap! app-state assoc :dot-chain []))
(defroute "/:filter" [filter] (swap! app-state assoc :showing (keyword filter)))

(def history (History.))

(events/listen history EventType.NAVIGATE
  (fn [e] (secretary/dispatch! (.-token e))))

(.setEnabled history true)

;; =============================================================================
;; app state stores :board, dot-index fn to calc dot index, :dot-chain and score.

; {:board [[ ; a vec of vec, each dot pos is a tuple has :color and :elem of html
;       {:color :blue, :elem #<[object HTMLDivElement]>} 
;       {:color :blue, :elem #<[object HTMLDivElement]>} 
;       {:color :purple, :elem #<[object HTMLDivElement]>} 
;    ]], 
;   :dot-index #<function (a) { ... }>, 
;   :dot-chain [], 
;   :score 0, 
;   :exclude-color nil} 
(defn setup-game-state []
  (let [state {:board (create-board)}]
    (let [board-offset ((juxt :left :top) (offset ($ ".dots-game .board")))]
      (assoc state
             :screen :newgame 
             :dot-index (partial dot-index board-offset)
             :dot-chain [] 
             :score 0
             :exclude-color []))))

; when starting, screen is newgame, 
; :board is a vec of vec, each dot pos is a tuple has :color and :ele of html
(def app-state (atom (setup-game-state)))


; --------------------------------------------------------------------------
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


; the main section for game board
(defn main [{:keys [board screen dot-chain] :as app} comm]
  (dom/section #js {:id "main" :style (hidden (= :newgame screen))}
    (dom/header #js {:id "header"}
      (dom/div #js {:className "header"} (str "Time"))
      (dom/div #js {:className "header"} (str "Score")))
    (dom/div #js {:className "board-area"}
      (dom/div #js {:className "chain-line"})
      (dom/div #js {:className "dot-highlights"})
      (dom/div #js {:className "board"}
        (make-dots app comm)))))

; mapv add-dots-to-board (state :board)
; doseq {:kesy [elem] dots}
(defn make-dots
  [app comm]
  (let [board (:board app)]
    (apply dom/div #js {:className (str "dot levelish " color " level-" level)}
      (om/build-all dots/dot board
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
