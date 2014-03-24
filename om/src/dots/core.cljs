(ns dots.core
  (:require
    [cljs.core.async :as async
      :refer [<! >! chan close! sliding-buffer put! alts! timeout]]
    [jayq.core :refer [$ append ajax inner css $deferred when done 
                       resolve pipe on bind attr offset] :as jq]
    [jayq.util :refer [log]]
    [crate.core :as crate]
    [clojure.string :refer [join blank? replace-first]]
    [clojure.set :refer [union]]
    [dots.board :refer [create-board start-screen render-screen score-screen  render-score
                        render-view render-position-updates render-remove-dots
                        render-dot-chain-update erase-dot-chain transition-dot-chain-state
                        dot-colors dot-color dot-index add-missing-dots
                        flash-color-on flash-color-off
                        dot-positions-for-focused-color]])
  (:require-macros [cljs.core.async.macros :as m :refer [go go-loop alt!]]))


;
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
  (let [init-state {:board (create-board)}]
    (let [board-offset ((juxt :left :top) (offset ($ ".dots-game .board")))]
      (assoc init-state 
             :dot-index (partial dot-index board-offset)
             :dot-chain [] 
             :score 0))))


(defn game-timer [seconds]
  (go-loop [timeout-chan (timeout 1000)  ; timer time out every second 
            count-down seconds]
    (inner ($ ".time-val") count-down) ; update UI
    (<! timeout-chan)  ; sequence programming, block 1 second
    (if (zero? count-down)
      count-down
      (recur (timeout 1000) (dec count-down)))))


; game loop on each draw gesture. when gesture done, draw dots in draw-chan stored in
; state map :dot-chain. remove those dots, and recur by add missing dots.
(defn game-loop [init-state draw-chan]
  (let [game-over-timeout (game-timer 600)]
    ; go-loop on state, state changes on each draw gesture.
    (go-loop [state init-state]
      (render-score state)
      (render-position-updates state)
      (let [state (add-missing-dots state)]
        (<! (timeout 300))
        (render-position-updates state)
        ; wait for the reading of draw-chan ret drawing dots in state map :dot-chain
        (let [[state ch] (alts! [(get-dots-to-remove draw-chan state) game-over-timeout])]
          (if (= ch game-over-timeout)
            state ;; leave game loop
            (recur  ; dots in draw-chan get maps to vec pos index and store in :dot-chain in state map
              (let [{:keys [dot-chain exclude-color]} state]  
                (log "game loop recur " dot-chain)  ; dot-chain = [[0 4] [1 4]]
                (if (< 1 (count dot-chain))
                  (-> state
                      (render-remove-dots dot-chain)
                      (assoc :score (+ (state :score) (count (set dot-chain)))
                             :exclude-color exclude-color))
                  state)
                ))))))))



; collect draw msg from body into draw-chan, goloop remove all dots in draw-chan
(defn app-loop []
  (let [draw-chan (draw-chan "body")
        start-chan (click-chan ".dots-game .start-new-game" :start-new-game)]
    (go
      (render-screen (start-screen))
      (<! (multi-wait-until #(= [:start-new-game] %) [start-chan draw-chan]))
      (loop []
        (let [{:keys [score]} (<! (game-loop (setup-game-state) draw-chan))]
          (render-screen (score-screen score))
          (<! (multi-wait-until #(= [:start-new-game] %) [start-chan draw-chan]))       
          (recur))))))

(app-loop)