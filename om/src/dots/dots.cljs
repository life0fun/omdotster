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
            [dots.dot-chan :as dot-chan]
            [dots.board :refer [create-board render-screen score-screen  render-score
                        render-view render-position-updates render-remove-dots
                        render-dot-chain-update erase-dot-chain transition-dot-chain-state
                        get-dot-div dot-colors dot-color dot-index add-missing-dots
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
  (let [state {:board (create-board)}
        drawchan (dot-chan/draw-chan "body")  ; draw chan collect draw evt on body
       ]
    (assoc state
           :screen :newgame
           :draw-chan drawchan
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

; om transact set app state to:screen upon login chan evted, will trigger re-render.
(defn set-state-screen [app start-time]
  (om/transact! app :screen
    (fn [screen] 
      (log "handle event set state screen to :start ") :start)
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
    :newgame (set-state-screen app val)
    :cancel  (cancel-action app)
    nil))


; =============================================================================
; thread dynamic var record render start
(def render-start nil)

; --------------------------------------------------------------------------
; create start screen with new game button
; pass in app state and login-chan so to send back evt to app
(defn login-screen [app login-chan]
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
                                     (put! login-chan [:newgame (now)]))} 
                   "new game")))))


; create React component for login-component and render update.
; login chan msg tuple [type value]
(defn login-component [{:keys [dots screen] :as app} owner]
  (reify
    om/IWillMount   ; called once upon component mount to DOM.
    (will-mount [_]
      (let [login-chan (chan)]  ; create chan upon mount 
        (om/set-state! owner :login-chan login-chan)
        ; once mounted, park a thread to process login chan evt
        (go-loop []
          (let [[type value] (<! login-chan)]  ; block on click start
            (when (== :newgame type)     ; upon newgame evt, set screen state, re-render.
              (set-state-screen app val))))))

    om/IWillUpdate
    (will-update [_ _ _] 
      (set! render-start (now)))  ; update render-start
    
    om/IDidUpdate
    (did-update [_ _ _]
      (let [ms (- (.valueOf (now)) (.valueOf render-start))]
        (log "did-update " ms)))
    
    om/IRenderState
    (render-state [_ {:keys [login-chan]}]  ; render on every change
      (log "render-state :screen " screen)
      (if (= screen :newgame)
        (login-screen app login-chan)  ; pass login-chan to coll evt from login screen
        ; board-component take over dots-game-container and start rendering
        (om/root board-component app-state
          {:target (.getElementById js/document "dots-game-container")}))
        )
  ))

; --------------------------------------------------------------------------
; mapv add-dots-to-board (state :board)
; doseq {:kesy [elem] dots}
; get vec of vec dots from state board, extract :elem, ret a vec of divs
; (dom/div #js {:className (str "dot levelish") :style style})
(defn make-dots-board
  [app]
  (let [board (:board app)
        dots (mapcat get-dot-div board)]
    dots))

; board screen with each dot a div 
(defn board-screen [{:keys [board screen dot-chain] :as app}]
  (dom/div #js {:id "main" :className "dots-game"}
    (dom/header #js {:id "header"}
      (dom/div #js {:className "header"} (str "Time"))
      (dom/div #js {:className "header"} (str "Score")))
    (dom/div #js {:className "board-area"}
      (dom/div #js {:className "chain-line"})
      (dom/div #js {:className "dot-highlights"})
      ; apply unwrap list of dom/div reted from make-dots-board, make them
      ; as individual args to dom/div.  (dom/div (dom/div) (dom/div) ...)
      (apply dom/div #js {:className "board"
                          :onClick (fn [e] (log "board click"))}
        ;(dom/div #js {:className "dot levelish red level-1" :style #js {:top "-112px", :left "158px"}})
        (make-dots-board app)
        ))))

; create React component for board-component and render update.
(defn board-component 
  [{:keys [dots screen] :as app} owner]
  (reify
    om/IWillMount   ; called once upon component mount to DOM.
    (will-mount [_]
      (log "board-screen mounted...")
      ;(game-loop app (:draw-chan app))  ; once mounted, park thread to render loop
      )

    om/IWillUpdate
    (will-update [_ _ _] 
      (set! render-start (now)))  ; update render-start
    
    om/IDidUpdate
    (did-update [_ _ _]
      (let [ms (- (.valueOf (now)) (.valueOf render-start))]
        (log "did-update " ms)))
    
    om/IRenderState
    (render-state [_ {:keys [comm]}]  ; render on every change
      (log "board screen rendering " screen)
      (board-screen app comm)
      )
  ))

; at very beginning, render login screen component inside dots-game-container
(om/root login-component app-state
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
