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
            ;[dots.dot-chan :as dot-chan]
            [dots.board :refer [create-board render-screen score-screen  render-score
                        render-view render-position-updates render-remove-dots
                        render-dot-chain-update erase-dot-chain transition-dot-chain-state
                        get-dot-elem dot-colors dot-color dot-index add-missing-dots
                        flash-color-on flash-color-off
                        dot-positions-for-focused-color]]
            )
  (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]]
                   [secretary.macros :refer [defroute]])
  (:import [goog History]
           [goog.history EventType]))

;
; when creating React component, if it only contains primitive elements,
; just use dom/div, dom/input, dom/a, etc; if nested, then build, build-all
; if nested React components, then need to om/build the nested components.
; (apply dom/ul #js {:id "x:} om/build-all dot dots {})
;

; every change of app-state causes virtual DOM re-rendering !
; om/transact! ([cursor korks f tag])
; cursor is cursor/path in to app-state, 
; korks is an optional key or sequence of keys to access in the cursor
; tag is optional :keyword or [:keyword values]


(enable-console-print!)

(def ENTER_KEY 13)

(declare toggle-all)

; =============================================================================
; order matters. def app-state on top, or declare before refer !
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
; (defn setup-game-state []
;   (let [state {:board [[]]}]
;     (let [board-offset ((juxt :left :top) (offset ($ ".dots-game .board")))]
;       (assoc state
;              :screen :newgame 
;              :dot-index (partial dot-index board-offset)
;              :dot-chain [] 
;              :score 0
;              :exclude-color []))))

(defn setup-game-state []
  (let [state {:board (create-board)}]
    (assoc state
           :screen :newgame
           :dot-chain [] 
           :score 0
           :exclude-color [])))

; when starting, screen is newgame, 
; :board is a vec of vec, each dot pos is a tuple has :color and :ele of html
(def app-state (atom (setup-game-state)))

;; =============================================================================
;; Routing
(defroute "/" [] (swap! app-state assoc :dot-chain []))
(defroute "/:filter" [filter] (swap! app-state assoc :showing (keyword filter)))

(def history (History.))

(events/listen history EventType.NAVIGATE
  (fn [e] (secretary/dispatch! (.-token e))))

(.setEnabled history true)


; --------------------------------------------------------------------------
; create start screen with new game button
; pass in app state and comm chan so to send back evt to app
(defn start-screen [app comm]
  (dom/div #js {:className "dots-game"}
    (dom/div #js {:className "notice-square"}
      (dom/div #js {:className "marq"}
        (dom/span #js {:className "purple"} (str "S"))
        (dom/span #js {:className "purple"} (str "C"))
        (dom/span #js {:className "yellow"} (str "O"))
        (dom/span #js {:className "green"} (str "R"))
        (dom/span #js {:className "red"} (str "E"))
        (dom/span nil)
        (dom/span #js {:className "red"} (str "2")))
      (dom/div #js {:className "control-area"}
        (dom/a #js {:className "start-new-game" :href "#"
                    :onClick (fn [e] (log "start game clicked") 
                                     (put! comm [:newgame (now)]))} 
                   "new game")))))


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
        (dom/div #js {:id "dot1" :className "dot levelish red level-0" :style "top:-112px; left: 158px;"} 
          (dom/a #js {} "xxx"))
        (dom/div #js {:id "dot2" :className "dot levelish yellow level-0" :style "top:-112px; left: 158px;"} 
          (dom/a #js {} "yyy"))
        ;(make-dots-board app comm)
        ))))

; mapv add-dots-to-board (state :board)
; doseq {:kesy [elem] dots}
; get vec of vec dots from state board, extract :elem, ret a vec of divs
; (dom/div #js {:className (str "dot levelish") :style style})
(defn make-dots-board
  [app comm]
  (let [board (:board app)]
    ;(apply dom/div #js {:className (:classname %) :style (:style %)}
    ; (apply dom/div #js {:className "dot levelish red" :style "top:-112px; left: 158px;"}
    ;        (first board))
  ))

;; =============================================================================
(defn handle-keydown [e app owner]
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

(defn start-new-game [app start-time]
  (om/transact! app :screen
    (fn [screen] (log "handle event set game start! ") :start)
    [:start start-time]))

(defn edit-todo [app {:keys [id]}] (om/update! app :editing id))

(defn save-todos [app] (om/update! app :editing nil))

(defn cancel-action [app] (om/update! app :editing nil))

(defn clear-completed [app]
  (om/transact! app :todos
    (fn [todos] (into [] (remove :completed todos)))))

(defn handle-event [type app val]
  (log "handle-event type " type " val " val)
  (case type
    :newgame (start-new-game app val)
    :cancel  (cancel-action app)
    nil))


; =============================================================================
; thread dynamic var record render start
(def render-start nil)

; dots-app fn create React component. comm msg tuple in [type value]
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
    (will-update [_ _ _] (set! render-start (now)))  ; update render-start
    om/IDidUpdate
    (did-update [_ _ _]
      (let [ms (- (.valueOf (now)) (.valueOf render-start))]
        (log "did-update " ms)
        ))
    om/IRenderState
    (render-state [_ {:keys [comm]}]  ; render on every change
      (log "render-state :screen " screen)
      (if (= screen :newgame)
        (start-screen app comm)
        (main app comm)))))

; start render loop for dots-app component, inside dots-game-container div
(om/root dots-app app-state
  {:target (.getElementById js/document "dots-game-container")})

(dom/render
  (dom/div nil
    (dom/p nil "Dots Game")
    (dom/p nil
      (dom/a #js {:href "http://github.com/dotster"}))
    (dom/p nil
      #js ["Part of"
           (dom/a #js {:href "http://dots.com"} "dots board")]))
  (.getElementById js/document "info"))
