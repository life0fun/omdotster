(ns dots.dot-chan
  (:require [cljs.core.async :as async
              :refer [<! >! chan close! sliding-buffer put! alts! timeout]]
            [dots.utils :refer [now hidden]]
            [clojure.string :refer [join blank? replace-first] :as string]
            [clojure.set :refer [union]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            
            [jayq.core :refer [$ append ajax inner css $deferred when done 
                               resolve pipe on bind attr offset] :as jq]
            [jayq.util :refer [log]]
            [dots.board :refer [board-size create-board render-screen score-screen render-score
                        render-view render-position-updates render-remove-dots
                        render-dot-chain-update erase-dot-chain transition-dot-chain-state
                        dot-colors dot-color dot-index add-missing-dots
                        flash-color-on flash-color-off
                        dot-positions-for-focused-color] :as board])
  (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]]
                   [secretary.macros :refer [defroute]]))


; ----------------------------------------------------------------------------
; go-loop [state] block read chan, recur conj state data.
; go-loop [msg (<! chan)] (if (pred msg) (>! msg out-chan)) (recur (<! chan))
; read chan in body of go-loop, recur with updated conj new state 
; or put predicated msg into out chan and return out chan.
; break out when expected msg is recvd.
; ret output chan, or waited for msg value.
; 
; go-loop [ msg (<! chan)]
;   when (= :drawstart msg)
;     (>! out-chan msg)
;     go-loop [msg (<! chan)]
;       (>! out-chan msg)
;       if (= :draw msg)
;         (recur (<! chan))  
;   (recur (<! chan))
;
; game-loop on each draw gester. alts! draw-chan and timeout-chan; 
; get dots to remove from draw-chan ends and dots in :dot-chain in state map.
;   go-loop [state init-state]
;     [value ch] (alts! [(get-dots-to-remove draw-chan state) timeout-chan])
;     (recur (-> state (render-remove-dots dot-chain) (assoc :score x)))
;
; ----------------------------------------------------------------------------

; for transient animiation, you can om.core/set-state! w/o re-render
;   (defn component [cursor owner opts]
;     (reify
;       om/IInitState
;       (init-state [_]
;         {:matters true
;          :ignore-me "changing me doesn't affect rerender"})
;       om/IShouldUpdate
;       (should-update [this next-props next-state]
;         (let [prev-state (om/get-render-state owner)
;               ignore-keys [:ignore-me]]
;           (not (apply = (map #(apply dissoc % ignore-keys) [prev-state next-state])))))
;       om/IRenderState
;       (render-state [this state]
;         ; render something
;         )))


(def ESCAPE_KEY 27)
(def ENTER_KEY 13)

;; -----------------------------------------------------------------------------
;; Event Handlers

(defn submit [e todo owner comm]
  (when-let [edit-text (om/get-state owner :edit-text)]
    (if-not (string/blank? (.trim edit-text))
      (do
        (om/update! todo :title edit-text)
        (put! comm [:save @todo]))
      (put! comm [:destroy @todo])))
  false)

(defn edit [e todo owner comm]
  (let [todo @todo
        node (om/get-node owner "editField")]
    (put! comm [:edit todo])
    (doto owner
      (om/set-state! :needs-focus true)
      (om/set-state! :edit-text (:title todo)))))

(defn key-down [e todo owner comm]
  (condp == (.-keyCode e)
    ESCAPE_KEY (let [todo @todo]
                 (om/set-state! owner :edit-text (:title todo))
                 (put! comm [:cancel todo]))
    ENTER_KEY  (submit e todo owner comm)
    nil))

(defn change [e todo owner]
  (om/set-state! owner :edit-text (.. e -target -value)))


; each dot is a div, we do not have complicated evt hdl for dot div.
; <div class="dot levelish red level-5" style="top:-112px; left: 23px;">
(defn dot-item [dot owner]
  (reify
    om/IInitState
    (init-state [_]
      {:style (:style dot)})
    om/IRenderState
    (render-state [_ {:keys [comm] :as state}]
      (dom/div #js {:className "dot"}))))


; ------------------- chan related ----------------------------
; multi-wait on a list of chans, block unitl predicated event appear
(defn multi-wait-until [pred chans]
  (go-loop []
    (let [[value ch] (alts! chans)]
      (if (pred value) 
        value 
        (recur)))))

; click chan contains click evt put in by click event handler on the selector.
(defn click-chan [selector msg-name]
  (let [out-chan (chan)
        handler (fn [e] (jq/prevent e) (put! out-chan [msg-name]))]
    (on ($ "body") :click selector {} handler)
    (on ($ "body") "touchend" selector {} handler)
    out-chan))

; Evt hdl injects mousemove evt to out-chan. [:draw {:x :y}]
(defn mouseevent-chan [out-chan selector event msg-name]
  (bind ($ selector) event
        #(do
          (put! out-chan [msg-name {:x (.-pageX %) :y (.-pageY %)}]))))

; Evt hdl injects touch move evt to out-chan. [msg-name {:x :y}]
(defn touchevent-chan [out-chan selector event msg-name]
  (bind ($ selector) event
        #(let [touch (aget (.-touches (.-originalEvent %)) 0)]
          (put! out-chan [msg-name {:x (.-pageX touch) :y (.-pageY touch)}]))))

; div's mousedown evt hdl emits :drawstart msg into the passed-in chan
(defn drawstart-chan [ichan selector]
  (mouseevent-chan ichan selector "mousedown" :drawstart)
  (touchevent-chan ichan selector "touchstart" :drawstart))

; div's mouseup evt hdl emits :drawend msg into the passed-in chan
(defn drawend-chan [ichan selector]
  (mouseevent-chan ichan selector "mouseup" :drawend)
  (mouseevent-chan ichan selector "touchend" :drawend))

; div's mousemove hdl emits [:draw {:x 1 :y 2}] into passed-in chan.
(defn drawer-chan [ichan selector]
  (mouseevent-chan ichan selector "mousemove" :draw)
  (touchevent-chan ichan selector "touchmove" :draw))

; inner go-loop read :draw msg and put them into out-chan, recur only when :draw
(defn get-drawing [draw-ichan draw-ochan]
  (go-loop [msg (<! draw-ichan)]
    (put! draw-ochan msg)
    (if (= (first msg) :draw) ; recur only when :draw, ret otherwise.
      (recur (<! draw-ichan)))))

; this fn encap details of go-loop reading and filtering of :draw evts
; and ret draw-ochan that contains [ :draw-start :draw :draw ... :draw-end ]
; div's mouse click or move evts handler put msg into draw-ichan.
; go-loop read chan. when msg is :start, inner go-loop to relay :draw msg to draw-ochan. 
(defn draw-chan [selector]  ; called with selector = "body", click and mousemove evt in body div
  (let [draw-ichan (chan) 
        draw-ochan (chan)]
    (drawstart-chan draw-ichan selector)
    (drawend-chan   draw-ichan selector)
    (drawer-chan    draw-ichan selector)
    ; outer goloop read msg from chan, start inner go-loop when :draw-start
    (go-loop [[msg-name _ :as msg] (<! draw-ichan)]
      ; :drawstart, state changes to :draw msg with inner goloop. inner loop ret only when :drawend
      (when (= msg-name :drawstart)
        (put! draw-ochan msg)
        (<! (get-drawing draw-ichan draw-ochan)))
      (recur (<! draw-ichan)))
    draw-ochan))


(defn dot-chain-cycle? [dot-chain]
  (and (< 3 (count dot-chain))
       ((set (butlast dot-chain)) (last dot-chain))))

; goloop draw-chan, where [:draw-start :draw :draw-end], read msg one by one.
; map :draw x,y to dots with board dots index pos, and store dots to state map :dot-chain.
; when this ret, one draw gesture done, draw-chan :drawend, and all draw dots in :dot-chain in state map.
(defn get-dots-to-remove   ; ret state map that contains :dot-chain
  [board dot-chain draw-chan board-offset]
  (go-loop [last-dot-chain nil
            dot-chain dot-chain]
    ; render dot chain animation.
    (render-dot-chain-update board last-dot-chain dot-chain)
    (if (dot-chain-cycle? dot-chain)
      (let [color (dot-color board (first dot-chain))] ; color of first dot in dot-chain
        (flash-color-on color) ; add flash class to .board-area
        ; blocking wait until :drawend from draw-chan, then conj to :dot-chain
        (<! (multi-wait-until (fn [[msg _]] (= msg :drawend)) [draw-chan]))
        (flash-color-off color)
        (erase-dot-chain)  ; just reset (inner ($ ".dots-game .dot-highlights") ""))
        {:board board
         :dot-chain (dot-positions-for-focused-color board dot-chain) 
         :exclude-color color})

      ; blocking on draw-chan until draw-chan ret a chan contains [:draw-start :draw ... :drawend ]
      ; read chan could block, and each read rets one and only one event.
      (let [[msg point] (<! draw-chan)]
        (if (= msg :drawend)
          (do
            (erase-dot-chain)   ; reset .dot-highlights ""
            (log "draw-chan :drawend " msg " " point " " dot-chain)
            {:board board 
             :dot-chain dot-chain 
             :exclude-color exclude-color}
          )
          (recur ; recur read :draw msg from draw-chan and conj to :dot-chain in state map.
            dot-chain
            (if-let [dot-pos (board/dot-index board-offset point)]
              (transition-dot-chain-state board dot-chain dot-pos)
              dot-chain)
            ))))))

; game timer recur read timeout-chan until count-down zero
(defn game-timer [seconds]
  (go-loop [timeout-chan (timeout 1000)  ; timer time out every second 
            count-down seconds]
    (inner ($ ".time-val") count-down) ; update UI
    (<! timeout-chan)  ; sequence programming, block 1 second
    (if (zero? count-down)
      count-down
      (recur (timeout 1000) (dec count-down)))))

;; ============================================================================= 
; {:board [[ ; a vec of vec, each dot pos is a tuple has :color and :elem of html
;       {:color :blue, :elem #<[object HTMLDivElement]>}.
;       {:color :blue, :elem #<[object HTMLDivElement]>}.
;       {:color :purple, :elem #<[object HTMLDivElement]>}.
;    ]],.
;   :dot-index #<function (a) { ... }>,.
;   :dot-chain [],.
;   :score 0,.
;   :exclude-color nil}.
; deprecated, only used within this ns.
(defn setup-game-state []                                                                                                                                                         
  (let [init-state {:board (create-board)}]
    (render-view init-state)
    (let [board-offset ((juxt :left :top) (offset ($ ".dots-game .board")))]
      (assoc init-state
             :dot-index (partial dot-index board-offset)
             :dot-chain []
             :score 0))))

; game loop on each draw gesture. when gesture done, draw dots in draw-chan stored in
; state map :dot-chain. remove those dots, and recur by add missing dots.
(defn game-loop [app]
  ; go block multi-wait until draw chan returns dot-chain 
  (go
    (let [app-state @app
          board (:board app-state)
          dot-chain (:dot-chain app-state)
          exclude-color (:exclude-color app-state)
          ;draw-chan (:draw-chan app-state)
          draw-chan (draw-chan "body")
          board-offset (:board-offset app-state)
          game-over-timeout (game-timer 600)
          board-offset ((juxt :left :top) (offset ($ ".dots-game .board"))) 
         ]
      ;(render-score state)
      ;(render-position-updates state)
      (<! (timeout 300))
      (log "game loop go-loop dot-chain " dot-chain " exclude-color " exclude-color)
      (let [[chan-value ch]
              (alts! [(get-dots-to-remove board dot-chain draw-chan board-offset) 
                      game-over-timeout])]
        (if (= ch game-over-timeout)
          board   ; game end, return board upon time out
          (let [{:keys [board dot-chain exclude-color]} chan-value
                r-dot-chain (map (fn [[xpos ypos]] [xpos (- (dec board-size) ypos)]) dot-chain)
                newboard (render-remove-dots board r-dot-chain exclude-color)]
            (log "game loop get-dots-to-remove dot-chain " dot-chain " " r-dot-chain)  ; dot-chain = [[0 4] [1 4]]
            ; (when (< 1 (count dot-chain))
            (om/transact! app :board
              (fn [old-board] newboard)) ; dot pos reverse
          ))
        )
      )
  ))

; collect draw msg from body into draw-chan, goloop remove all dots in draw-chan
(defn app-loop []
  (let [draw-chan (draw-chan "body")
        start-chan (click-chan ".dots-game .start-new-game" :start-new-game)]
    (go
      ;(render-screen (start-screen))
      (<! (multi-wait-until #(= [:start-new-game] %) [start-chan draw-chan]))
      (loop []
        (let [{:keys [score]} (<! (game-loop (setup-game-state) draw-chan))]
          (render-screen (score-screen score))
          (<! (multi-wait-until #(= [:start-new-game] %) [start-chan draw-chan]))       
          (recur))))))

;; (app-loop)

