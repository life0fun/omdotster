(ns dots.board
  (:require
    [cljs.core.async :as async
      :refer [<! >! chan close! sliding-buffer put! alts! timeout]]
    
    [jayq.core :refer [$ append ajax inner css $deferred when done 
                       resolve pipe on bind attr offset] :as jq]
    [jayq.util :refer [log]]
    
    ;[crate.core :as crate]
    [clojure.string :refer [join blank? replace-first]]
    [clojure.set :refer [union]]
    
    [om.dom :as dom :include-macros true])
  (:require-macros [cljs.core.async.macros :as m :refer [go go-loop alt!]]))


; when creating dom/div with style, style takes a map with keys, not string
; (dom/div #js {:className "dot" :style #js {:top "-112px" :left "158px"}}
; if you provide string, you got minification exception.

;
; crate/html is used to generate html for the UI, and attach to div
; 1. crate-dot {:color color :elem (crate/html (... [xpos ypos] color))}
; 2. render-screen for .dot-game-container
; 3. render-view for (:board state), -> [:div.dots-game [:div.board ...]]
; 4. render dot chain update for .chain-line an d.dot-highlights
;


;<div class="board">
;   <div class="dot levelish red level-5" style="top:-112px; left: 23px;"></div>
;   <div class="dot levelish yellow level-5" style="top:-112px; left: 23px;"></div>
;   <div class="dot levelish blue level-4" style="top:-112px; left: 68px;"></div>
;   <div class="dot levelish green level-0 level-0-from0" style="top:-112px; left: 248px;"></div>
;
;   <div class="dot levelish purple level-0 level-0-from0" style="top:-112px; left: 248px;"></div>

(def abs #(.abs js/Math %))

; offscreen dot 
(def board-size 6)
(def offscreen-dot-position 8)

(def dot-size 22)
(def grid-unit-size 45)
(def corner-offset (- grid-unit-size dot-size))
(def pos->coord #(+ corner-offset (* grid-unit-size %)))

(def reverse-board-position (partial - (dec board-size)))
(def offscreen-offset (-> offscreen-dot-position reverse-board-position pos->coord -))

(def dot-colors [:blue :green :yellow :purple :red])
(def number-colors (count dot-colors))


(defn rand-colors [exclude-color]
  ;(log "rand-colors " (prn-str exclude-color))
  (let [colors (if exclude-color 
                   (vec (remove (partial = exclude-color) dot-colors))
                   dot-colors)
        number-colors (if exclude-color (dec number-colors) number-colors)]
    (map #(get colors (rand-int %)) (repeat number-colors))))

(defn colorize-word [word]
  (map (fn [x c] [:span {:class (name c)} x]) word (rand-colors)))

; ----------- game screen and board -----------------

; set div's inner html to crate/html [:div.dots-game]
(defn render-screen [screen]
  ; (let [view-dom (crate/html screen)]
  ;   (inner ($ ".dots-game-container") view-dom)))
  screen)

(defn render-score [{:keys [score]}]
  (inner ($ ".score-val") score))

(defn erase-dot-chain []
  (inner ($ ".dots-game .chain-line") "")
  (inner ($ ".dots-game .dot-highlights") ""))

; append dots html to board div, dot's elem already is html in create-dot
(defn add-dots-to-board [dots]
  (doseq [{:keys [elem]} dots]
    (append ($ ".dots-game .board") elem)))

(defn render-view [state]
  ; (let [view-dom (crate/html (board state))]
  ;     (inner ($ ".dots-game-container") view-dom)
  ;     (mapv add-dots-to-board (state :board))))
  state)


; fn inside state map :dot-index = ((partial dot-index board-offset) point)
; map x,y co-ordinate into board matrix i,j
(defn dot-index 
  [offset {:keys [x y]}]  ; offset is board offset
  (let [[x y] (map - [x y] offset [12 12])]  ; (x-offset-12, y-offset-12)
    (let [ypos (reverse-board-position (int (/ y grid-unit-size)))
          xpos (int (/ x grid-unit-size))]
      (if (and (> board-size ypos -1) (> board-size xpos -1))
        [xpos ypos]))))

; board is vector of vector.
; {:board [[{:color :blue :ele #<[objec]>}]]}
; (defn dot-color [{:keys [board]} dot-pos]
;   (-> board (get-in dot-pos) :color))  ; get-in for nested map, and vector
; YPos in board is calc-ed from reverse board position, when index into board, reverse back.
(defn dot-color [board [xpos ypos]]
  (let [dot-pos [xpos (- (dec board-size) ypos)]
        color (-> board (get-in dot-pos) :color)]
    (log "dot-color " dot-pos color)
    color))

; ------------------ dot pos destruct to x, y -----------------------------
(defn pos->corner-coord [[xpos ypos]]
  (mapv pos->coord [(reverse-board-position ypos) xpos]))

(defn pos->center-coord [dot-pos]
  (mapv #(+ (/ dot-size 2) %) (pos->corner-coord dot-pos)))

(defn top-coord-from-dot-elem [$elem]
  (- (int (last (re-matches #".*translate3d\(.*,(.*)px,.*\).*"
                            (attr $elem "style"))))
     offscreen-offset))

(defn top-pos-from-dot-elem [$elem]
  (if-let [[_ pos-str] (re-matches #".*level-(\d).*" (attr $elem "class"))]
    (reverse-board-position (int pos-str))))

(defn at-correct-postion? [dot [_ expected-top]]
  (= expected-top (top-pos-from-dot-elem ($ (dot :elem)))))


; <div class="dot levelish yellow level-5" style="top:-112px; left: 23px;"></div>
; <div class="dot levelish blue level-4" style="top:-112px; left: 68px;"></div>
; <div class="dot levelish green level-0 level-0-from0" style="top:-112px; left: 248px;"></div>
; (defn starting-dot [[top-pos _] color]
;   (let [[start-top left] (pos->corner-coord [top-pos offscreen-dot-position])
;         style (str "top:" start-top "px; left: " left "px;")]
;     [:div {:class (str "dot levelish " (name color)) :style style}]))
(defn starting-dot [[top-pos _] color]
  (let [[start-top left] (pos->corner-coord [top-pos offscreen-dot-position])
        ;style (str "top:" start-top "px; left: " left "px;")
        style {:top (str start-top "px;") :left (str left "px;")}]
    style))

; (defn create-dot [xpos ypos color]
;   {:color color :elem (crate/html (starting-dot [xpos ypos] color))})

; ret a dot prop map where style is a nested map of {:top -2px :left 1px}
(defn create-dot [xpos ypos color]
  (let [style (starting-dot [xpos ypos] color)
        classname (str "dot levelish " (name color) " level-" ypos)
        dot {:color color :style style :classname classname :dot-id (str xpos "-" ypos)}]
    (log "create-dot x: " xpos " y: " ypos " " style " " color " " (:dot-id dot))
    dot))


; given a row of dots prop map, ret a list of dom/div
; JavaScript literal must use map or vector notation, so one more indirection
(defn get-dot-div [dots]
  (mapv (fn [dot]  ; dot prop map {:classname :color, :style {:top :left} }
          (let [style (:style dot)]
            (dom/div #js {:id (:dot-id dot)
                          :className (:classname dot)
                          :style #js {:top (:top style) :left (:left style)}})))
        dots))


; board is vec of vec.
; (def world (apply vector
;   (map (fn [_] (apply vector (map (fn [_] (ref (struct cell 0 0))) (range dim))))
;        (range dim))))
; <div class="dot levelish yellow level-5" style="top:-112px; left: 23px;"></div>
; <div class="dot levelish blue level-4" style="top:-112px; left: 68px;"></div>
(defn create-board [] 
  (vec
    (map-indexed  ; create-dot at row i, within each row, different colors.
      (fn [row x] 
        (vec    ; inner index is ypos, and map over a list of color.
          (map-indexed (partial create-dot row) (take board-size (rand-colors)))))
      (range board-size)  ; outer index is row, board-size.
      )))

; ; create-dot and append to board div, concat new dot to state[col] list.
(defn add-missing-dots-helper 
  [col-idx col exclude-color]
  (if (= (count col) board-size)
    col
    (let [new-dots (map create-dot
                        (repeat col-idx)
                        (repeat offscreen-dot-position)
                        (take (- board-size (count col)) (rand-colors exclude-color)))]
      (add-dots-to-board new-dots)
      (vec (concat col new-dots))
      col
    ))
  )

; given a state map, repopulate missing dots in board and reset excl color.
; (defn add-missing-dots
;   [{:keys [board exclude-color] :as state}]
;   (assoc state :board
;          (vec (map-indexed
;                 #(add-missing-dots-helper %1 %2 exclude-color)
;                 board))
;          :exclude-color nil))

; given a board and exclude color, ret a board with added dots
(defn add-missing-dots [board exclude-color]
  (vec (map-indexed
          #(add-missing-dots-helper %1 %2 exclude-color) ; col-index and a col of dots
          board)))

;; ------------------ css style and transition ------------------------------
(defn translate-top [top]
  (str "translate3d(0," (+ offscreen-offset top) "px,0) "))


; remove a dot by ($ele).remove, with some css animation.
; {:color :blue, :style {:top "-112px;", :left "23px;"}, :classname "dot levelish blue level-0"} 
(defn remove-dot [{:keys [dot-id] :as dot}]
  ; (go
    (log "remove-dot " dot-id)
    (let [$elem ($ dot-id)  ; select the dot
          top (-> (top-pos-from-dot-elem $elem) reverse-board-position pos->coord)
          trans (translate-top top)]
      (css $elem {"-webkit-transition" "all 0.2s"})
      (css $elem {"-webkit-transform"
                  (str trans " scale3d(0.1,0.1,0.1)")
                  "-moz-transform"
                  (str "translate(0," (+ offscreen-offset top) "px) scale(0.1,0.1)")
                  "-ms-transform"
                  (str "translate(0," (+ offscreen-offset top) "px) scale(0.1,0.1)")})
      ; wait animation
      (<! (timeout 150))
      (.remove ($ elem)))
  )

; remove dots by row
(defn render-remove-dots-row-helper 
  [dot-chain-set col]
  (let [dots-to-remove (keep-indexed #(if (dot-chain-set %1) %2) col)
        next_col     (keep-indexed #(if (not (dot-chain-set %1)) %2) col)]
    (doseq [dot dots-to-remove]
      (remove-dot dot))
    (vec next_col)))


; just update state board with new board
; (defn render-remove-dots [state dot-chain]
;   (let [dot-chain-groups  (group-by first dot-chain)
;         next_board (map-indexed #(render-remove-dots-row-helper
;                                     (set (map last (get dot-chain-groups %1))) 
;                                     %2)
;                                 (state :board))]
;     (assoc state :board (vec next_board))))

(defn render-remove-dots [board dot-chain]
  (let [dot-chain-groups  (group-by first dot-chain) ; {2 [[2 0]], 3 [[3 0]]}
        next_board (map-indexed #(render-remove-dots-row-helper
                                    (set (map last (get dot-chain-groups %1))) 
                                    %2)
                                board)]
    (log "render-remove-dots next board " (vec next_board))
    (vec next_board)))


; update dot by adding css class. 
(defn update-dot [dot pos]
  (if dot
    (go
      (let [$elem ($ (dot :elem))
            top (top-pos-from-dot-elem $elem)
            previous-level (if top (str "-from" (reverse-board-position top)) "")]
        (.addClass $elem (str "level-"
                              (reverse-board-position (last pos))
                              previous-level))))))

(defn render-position-updates-helper 
  [col-idx col]
  (go-loop [[dot & xd] col pos 0]
    (when (not (nil? dot))
      (when (not (at-correct-postion? dot [col-idx pos]))
        (<! (timeout 80))
        (update-dot dot [col-idx pos]))
      (recur xd (inc pos)))))

; after position updates, render state board
(defn render-position-updates 
  [{:keys [board]}]
  (doall
    (map-indexed
      #(render-position-updates-helper %1 %2)
      board)))


; double line in background in style when dots chained
(defn chain-element-templ 
  [last-pos pos color]
  (let [[top1 left1] (pos->center-coord last-pos)
        [top2 left2] (pos->center-coord pos)
        length (- grid-unit-size dot-size)
        vertical (= left1 left2)
        [width height] (if vertical [4 length] [length 4])
        [off-left off-top] (if vertical [-3 11] [11 -3])        
        style (str "width: " width "px;"
                   "height: " height "px;" 
                   "top: " (+ (min top1 top2) off-top) "px;"
                   "left: " (+ (min left1 left2) off-left) "px;")]
    [:div {:style style :class (str "line " (name (or color :blue)) (if (< width height) " vert" " horiz" ))}]))

; add dot-highlight style
(defn dot-highlight-templ 
  [pos color]
  (let [[top left] (pos->corner-coord pos)
        style (str "top:" top "px; left: " left "px;")]
    [:div {:style style :class (str "dot-highlight " (name color))}]))

(defn render-dot-chain-update 
  [last-state state]
  (let [last-dot-chain (:dot-chain last-state)
        dot-chain      (:dot-chain state)
        last-chain-length (count last-dot-chain)
        chain-length      (count dot-chain)]
    (when (and (not= last-chain-length chain-length) (pos? chain-length))
      (let [color (dot-color state (first dot-chain))
            length-diff (- chain-length last-chain-length)]
        (if (< 1 chain-length)
          (if (pos? length-diff)
            ; (append ($ ".dots-game .chain-line")
            ;         (crate/html (chain-element-templ
            ;                      (last (butlast dot-chain))
            ;                      (last dot-chain)
            ;                      color)))
            (.remove (.last ($ ".dots-game .chain-line .line"))))
          (inner ($ ".dots-game .chain-line") ""))
        ; (append ($ ".dots-game .dot-highlights")
        ;         (crate/html (dot-highlight-templ (last dot-chain) color))))
      ))))


; (defn dot-follows? [state prev-dot cur-dot]
;   (and (not= prev-dot cur-dot)
;        (or (nil? prev-dot)
;            (and
;             (= (dot-color state prev-dot) (dot-color state cur-dot))
;             (= 1 (apply + (mapv (comp abs -) cur-dot prev-dot)))))))
(defn dot-follows? [board prev-dot cur-dot]
  (and (not= prev-dot cur-dot)
       (or (nil? prev-dot)
           (and
            (= (dot-color board prev-dot) (dot-color board cur-dot))
            (= 1 (apply + (mapv (comp abs -) cur-dot prev-dot)))))))

(defn transition-dot-chain-state 
  [board dot-chain dot-pos]
  (let [follows (dot-follows? board (last dot-chain) dot-pos)
        dots (count dot-chain)
       ]
    (if follows
      (if (and (< 1 (count dot-chain))
               (= dot-pos (last (butlast dot-chain))))
        (vec (butlast dot-chain))
        (conj (or dot-chain []) dot-pos))
      dot-chain)))

(defn items-with-positions [items]
  (apply concat
         (map-indexed #(map-indexed (fn [i item] (assoc item :pos [%1 i])) %2) items)))

(defn get-all-color-dots 
  [board color]
  (filter #(= color (:color %)) (items-with-positions board)))

; (defn dot-positions-for-focused-color [state]
;   (let [color (dot-color state (-> state :dot-chain first))]
;       (vec (map :pos (get-all-color-dots state color)))))
(defn dot-positions-for-focused-color [board dot-chain]
  (let [color (dot-color board (first dot-chain))]
      (vec (map :pos (get-all-color-dots state color)))))


; flash class effect on board-area
(defn flash-class [color] (str (name color) "-trans"))

(defn flash-color-on [color]
  (.addClass ($ ".dots-game .board-area") (flash-class color)))

(defn flash-color-off [color]
  (.removeClass ($ ".dots-game .board-area") (flash-class color)))


