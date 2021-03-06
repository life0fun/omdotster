(ns dots.app
  (:require [goog.events :as events]
            [cljs.core.async :refer [put! <! chan]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [sablono.core :as html :refer-macros [html]]

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
                        dot-colors dot-color dot-index add-missing-dots
                        flash-color-on flash-color-off
                        dot-positions-for-focused-color] :as board]
            )
  (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]]
                   [secretary.macros :refer [defroute]])
  (:import [goog History]
           [goog.history EventType]))


; change of app-state causes virtual DOM re-rendering, and did-update will be called !
; om/transact! ([cursor korks f tag])
; cursor is cursor/path in to app-state, 
; korks is an optional key or sequence of keys to access in the cursor
; tag is optional :keyword or [:keyword values]

; cursor is IRef to app-state. you can not update cursor inside hook callback.
; don't do too much callback handler, use (go (put chan evet)...) put your logic back together!

; reify IRender must ret an Om component, a React component, or a some value that 
; React knows how to render. otherwise you get Minified exception.

; Two level of states. App has its own global state. Each component has its own state.
; cache hierarchy avoid name space collision and separation of global and component specific.

; - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
; om/root [f value options] : value is either a tree of cljs data structure or an atom.

; om/build item/todo-item todos[] {:init-state {:comm comm} :fn (fn [todo] ())}
; cursor should be an Om cursor onto the application state
; om/build takes a component fn with state cursor.
; om/component is a macro takes body and wrap into reify IRender to create component.
; html/html render hiccup clojure template intto html.

; dom/render can render html directly into react virtual dom !

; - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
; pattern: create chan in app, pass it to components as comm chan from comp to app.
; In this single page app, for simplify, we store everything in global app state.

; when vritual dom re-rendering, all components attached to the DOM re-rendered. 
; even though no change on the component. Hence, do not put no render logic there.
; re-render will be triggered by update to states, after update re-rendering, board 
; is already mounted, so only IWillUpdate and IDidUpdate will be called.


; Do not use traditional blocking call to get the return value.!!!!
; Always use chan to connect different components. read chan inside go or go-loop block.
; In IWillMount, (go-loop (let [evt (<! chan)] (handle-event evt)))

; event handler
; 1. install all event handlers for the component when mounted to DOM.
; 2. or install in-place when render each dom element in render-state.


(enable-console-print!)

(def ENTER_KEY 13)

(declare toggle-all)
(declare board-component)

; =============================================================================
; game state creates draw-chan to collect draw evt from body
(defn setup-game-state []
  (let [state {:board (create-board)}
        drawchan (dot-chan/draw-chan "body")  ; draw chan collect draw evt on body
       ]
    (assoc state
           :board-offset ((juxt :left :top) (offset ($ ".dots-game .board")))
           :screen :newgame
           :draw-chan drawchan
           :dot-chain []
           :score 0
           :exclude-color [])))

; Ref protect global app-state tree map; component fn gets a cursor to this tree map.
(def app-state (atom (setup-game-state)))

;; =============================================================================
;; Routing
;(defroute "/" [] (swap! app-state assoc :dot-chain []))
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
      (log "handle event set state screen to :start ") 
      :start)   ; ret :start
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
(defn login-screen [app login-chan]
  (html/html
    [:div.dots-game
      [:div.notice-square
        [:div.marq
          [:span.purple "S"]
          [:span.yellow "C"]
          [:span.green "O"]
          [:span.red "R"]
          [:span.purple "E"]
          [:span]
          [:span.red "2"]]
        [:div.control-area
          [:a.start-new-game 
            {:href "#" 
             :on-click (fn [e] 
                         (log "start game clicked, chan :newgame")
                         (put! login-chan [:newgame (now)]))
            } 
            "new game"]]
        ]]))

; create React component for login-component and render update.
; login chan msg tuple = [type value], first arg is app-state cursor.
(defn login-component [{:keys [dots screen] :as app} owner]
  (reify
    om/IWillMount   ; called once upon component mount to DOM.
    (will-mount [_]
      (log "login will-mount."))

    om/IDidMount
    (did-mount [this]
      (log "login mount " screen)
      (when (= screen :newgame)
        (let [login-chan (chan)]  ; create chan upon mount 
          (om/set-state! owner :login-chan login-chan)
          ; once mounted, park a thread to process login chan evt
          (go []
            (log "login-component mounted, wait for start button")
            (let [[type value] (<! login-chan)]  ; block on click start
              (log "login chan newgame event " type " " value)
              (when (== :newgame type)     ; upon newgame evt, set screen state, show board screen
                (set-state-screen app val)  ; update app state
                (log "login-component newgame started, om/root board render loop")
                (om/root board-component app-state 
                         {:target (.getElementById js/document "dots-game-container")})
                )))
          )))

    om/IWillUpdate
    (will-update [_ _ _] 
      (set! render-start (now)))  ; update render-start
    
    om/IDidUpdate
    (did-update [_ _ _]
      (let [ms (- (.valueOf (now)) (.valueOf render-start))]
        (log "login did-update " ms)))
    
    om/IRenderState
    (render-state [_ {:keys [login-chan]}]  ; render on every change
      (log "login component render :screen " screen)
      (if (= screen :newgame)
        (login-screen app login-chan)  ; pass login-chan to coll evt from login screen
        (board-screen app)
        ))
  ))

; --------------------------------------------------------------------------
; mapv add-dots-to-board (state :board)
; doseq {:kesy [elem] dots}
; get vec of vec dots from state board, extract :elem, ret a vec of divs

; (mapv (comp vec :elem) dot), Always need to ensure hiccup vector tag.
(defn make-dots-board
  [app]
  (let [board (:board app)
        dots (vec (mapcat #(mapv (comp vec :elem) %) board))
        ; dots (mapv :elem %) (first board))  XXX need convert to vec
        ; dots [[:div {:class "dot levelish purple level-0"}]
        ;         [:div {:class "dot levelish blue level-1"}]]
       ]
    dots))

; ; board screen with each dot a div 
; (defn board-screen [{:keys [board screen dot-chain] :as app}]
;   (dom/div #js {:id "main" :className "dots-game"}
;     (dom/header #js {:id "header"}
;       (dom/div #js {:className "heads"} (str "Time")
;         (dom/span #js {:className "time-val"} (str "601")))
;       (dom/div #js {:className "heads"} (str "Time")
;         (dom/span #js {:className "score-val"} (str "201"))))
;     (dom/div #js {:className "board-area"}
;       (dom/div #js {:className "chain-line"})
;       (dom/div #js {:className "dot-highlights"})
;       ; apply unwrap list of dom/div reted from make-dots-board, make them
;       ; as individual args to dom/div.  (dom/div (dom/div) (dom/div) ...)
;       (apply dom/div #js {:className "board"
;                           :onClick (fn [e] (log "board click"))}
;             ;(dom/div #js {:className "dot levelish red level-1" :style #js {:top "-112px", :left "158px"}})
;             (make-dots-board app))
;       )))

(defn board-screen [{:keys [board screen dot-chain] :as app}]
  (html/html
    [:div#main.dots-game
      [:header.header
        [:div.heads "Time"
          [:span.time-val "600"]]
        [:div.heads "Score"
          [:span.score-val "201"]]]
      [:div.board-area
        [:div.chain-line]
        [:div.dot-highlights]
        (into [:div.board] (vec (make-dots-board app)))
      ]
    ]))

; fn for React component for board-component and render update.
; pass app state cursor to game loop where app state will be deref and updated
(defn board-component 
  [{:keys [board dot-chain exclude-color screen draw-chan board-offset] :as app} owner]
  (reify
    om/InitState
    (init-state [_]    ; init component local state
      (log "board init-state")
      {:dot-chain []})

    ; called once when element is mounted to DOM.
    om/IWillMount
    (will-mount [_]
      (log "board will-mount, install event handler go-loop"))

    om/IDidMount
    (did-mount [this]
      (log "board did mount")
      (dot-chan/game-loop app))

    ; after mount, any state update will trigger re-render, invoke DidUpdate 
    om/IWillUpdate
    (will-update [_ _ _]
      (log "board will update") 
      (set! render-start (now)))  ; update render-start
    
    om/IDidUpdate
    (did-update [_ _ _]
      (let [ms (- (.valueOf (now)) (.valueOf render-start))]
        (log "board did-update " ms "ms")
        (dot-chan/game-loop app)
        ))
    
    om/IRenderState
    (render-state [_ {:keys [comm]}]  ; render on every change
      (log "board render-state: " screen)
      (board-screen app)
      )
  ))

; when start, render login screen component inside dots-game-container
; args is either a tree of cljs data structure, or atom to data structure.
(om/root login-component app-state
  {:target (.getElementById js/document "dots-game-container")})


; om/build takes a component fn with state cursor.
; om/component is a macro takes body and wrap into reify IRender to create component.
; html/html render hiccup clojure template to html,
(dom/render
  (html/html
    [:div
      [:p "Dots Game"]
      [:p
        [:a {:href "http://github.com/dotster"} "dotster"]]
      [:p
        [:a {:href "http://dotster.com"} "dotster"]]
    ])
  (.getElementById js/document "info"))
