(ns dots.dot-chan
  (:require [cljs.core.async :as async
              :refer [<! >! chan close! sliding-buffer put! alts! timeout]]
            [dots.utils :refer [now hidden]]
            [clojure.string :refer [join blank? replace-first] :as string]
            [clojure.set :refer [union]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]]
                   [secretary.macros :refer [defroute]])
  )

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

(defn dot-item [dot owner]
  (reify
    om/IInitState
    (init-state [_]
      {:edit-text (:title todo)})
    om/IDidUpdate
    (did-update [_ _ _]
      (when (and (:editing todo)
                 (om/get-state owner :needs-focus))
        (let [node (om/get-node owner "editField")
              len  (.. node -value -length)]
          (.focus node)
          (.setSelectionRange node len len))
        (om/set-state! owner :needs-focus nil)))
    om/IRenderState
    (render-state [_ {:keys [comm] :as state}]
      (let [class (cond-> ""
                    (:completed todo) (str "completed")
                    (:editing todo)   (str "editing"))]
        (dom/li #js {:className class :style (hidden (:hidden todo))}
          (dom/div #js {:className "view"}
            (dom/input
              #js {:className "toggle" :type "checkbox"
                   :checked (and (:completed todo) "checked")
                   :onChange (fn [_] (om/transact! todo :completed #(not %)))})
            (dom/label
              #js {:onDoubleClick #(edit % todo owner comm)}
              (:title todo))
            (dom/button
              #js {:className "destroy"
                   :onClick (fn [_] (put! comm [:destroy @todo]))}))
          (dom/input
            #js {:ref "editField" :className "edit"
                 :value (om/get-state owner :edit-text)
                 :onBlur #(submit % todo owner comm)
                 :onChange #(change % todo owner)
                 :onKeyDown #(key-down % todo owner comm)}))))))


; ------------------- chan related ----------------------------
; multi-wait on a list of chans, unitl predicated event appear
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

; Evt hdl injects mouse move evt to out-chan. [msg-name {:x :y}]
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
(defn draw-chan [selector]
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
  [draw-chan start-state]
  (go-loop [last-state nil 
            state start-state]
    (render-dot-chain-update last-state state)
    (if (dot-chain-cycle? (state :dot-chain))
      (let [color (dot-color state (-> state :dot-chain first))] ; color of first dot in dot-chain
        (flash-color-on color) ; add flash class to .board-area
        ; blocking wait until :drawend from draw-chan, then conj to :dot-chain
        (<! (multi-wait-until (fn [[msg _]] (= msg :drawend)) [draw-chan]))
        (flash-color-off color)
        (erase-dot-chain)  ; just reset (inner ($ ".dots-game .dot-highlights") ""))
        (assoc state :dot-chain (dot-positions-for-focused-color state) :exclude-color color))

      ; blocking on draw-chan until draw-chan ret a chan contains [:draw-start :draw ... :drawend ]
      ; read chan could block, and each read rets one and only one event.
      (let [[msg point] (<! draw-chan)]
        ; (log "get-dots-to-remove after read draw-chan " msg point)
        (if (= msg :drawend)
          (do (erase-dot-chain) state)  ; reset .dot-highlights ""
          (recur state   ; recur read :draw msg from draw-chan and conj to :dot-chain in state map.
                 (if-let [dot-pos ((state :dot-index) point)]
                    (assoc state :dot-chain (transition-dot-chain-state state dot-pos))
                    state)))))))


