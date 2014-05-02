(ns dots.board
  (:require
    [cljs.core.async :as async
      :refer [<! >! chan close! sliding-buffer put! alts! timeout]]
    
    [jayq.core :refer [$ append ajax inner css $deferred when done 
                       resolve pipe on bind attr offset] :as jq]
    [jayq.util :refer [log]]
    [sablono.core :as html :refer-macros [html render]]
    [clojure.string :refer [join blank? replace-first]]
    [clojure.set :refer [union]]
    
    [om.dom :as dom :include-macros true])
  (:require-macros [cljs.core.async.macros :as m :refer [go go-loop alt!]]))


; sablono maps hiccup [:div] to React's (dom/div), 
; use render to get render string. 

; when creating dom/div with style, style takes a map with keys, not string
; (dom/div #js {:className "dot" :style #js {:top "-112px" :left "158px"}}
; if you provide string, you got minification exception.

;
; 1. create-dot {:color color :elem (crate/html (... [xpos ypos] color))}
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

(defn items-with-positions [items]
  (apply concat
         (map-indexed #(map-indexed (fn [i item] (assoc item :pos [%1 i])) %2) items)))

; state map fn :dot-index = ((partial dot-index board-offset) point)
; map x,y co-ordinate into board matrix i,j, top,left = [0, 0]
(defn dot-index 
  [offset {:keys [x y]}]  ; offset is board offset
  (let [[x y] (map - [x y] offset [12 12])]  ; (x-offset-12, y-offset-12)
    (let [ypos (reverse-board-position (int (/ y grid-unit-size)))
          xpos (int (/ x grid-unit-size))]
      (if (and (> board-size ypos -1) (> board-size xpos -1))
        [xpos ypos]))))

;; ------------------ css style and transition ------------------------------
(defn translate-top [top]
  (str "translate3d(0," (+ offscreen-offset top) "px,0) "))

; flash class effect on board-area
(defn flash-class [color] (str (name color) "-trans"))

(defn flash-color-on [color]
  (.addClass ($ ".dots-game .board-area") (flash-class color)))

(defn flash-color-off [color]
  (.removeClass ($ ".dots-game .board-area") (flash-class color)))


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

; ----------- dot colors -----------------
(defn rand-colors [exclude-color]
  ;(log "rand-colors " (prn-str exclude-color))
  (let [colors (if exclude-color 
                   (vec (remove (partial = exclude-color) dot-colors))
                   dot-colors)
        number-colors (if exclude-color (dec number-colors) number-colors)]
    (map #(get colors (rand-int %)) (repeat number-colors))))

(defn colorize-word [word]
  (map (fn [x c] [:span {:class (name c)} x]) word (rand-colors)))

; board is vector of vector.
; {:board [[{:color :blue :ele #<[objec]>}]]}
; YPos in board is calc-ed from reverse board position, when index into board, reverse back.
(defn dot-color [board [xpos ypos]]
  (let [dot-pos [xpos (- (dec board-size) ypos)]
        color (-> board (get-in dot-pos) :color)]
    (log "dot-color " dot-pos color)
    color))


(defn get-all-color-dots 
  [board color]
  (filter #(= color (:color %)) (items-with-positions board)))

(defn dot-positions-for-focused-color [board dot-chain]
  (let [color (dot-color board (first dot-chain))]
      (vec (map :pos (get-all-color-dots state color)))))


; ------------------ create dots and board ------------------------------
; <div class="dot levelish yellow level-5" style="top:-112px; left: 23px;"></div>
; <div class="dot levelish blue level-4" style="top:-112px; left: 68px;"></div>
; <div class="dot levelish green level-0 level-0-from0" style="top:-112px; left: 248px;"></div>
; (defn starting-dot [[top-pos _] color]
;   (let [[start-top left] (pos->corner-coord [top-pos offscreen-dot-position])
;         style (str "top:" start-top "px; left: " left "px;")]
;     [:div {:class (str "dot levelish " (name color)) :style style}]))
(defn starting-dot [[top-pos _] color]
  (let [[start-top left] (pos->corner-coord [top-pos offscreen-dot-position])
        style {:top (str start-top "px;") :left (str left "px;")}]
    style))


; ret a dot prop map where style is a nested map of {:top -2px :left 1px}
(defn create-dot [xpos ypos color]
  (let [style (starting-dot [xpos ypos] color)
        classname (str "dot levelish " (name color) " level-" ypos)
        dot {:color color :style style :classname classname :dot-id (str xpos "-" ypos)
             :elem (vec [:div {:id (str xpos "-" ypos) :class classname :style style}])
             :removed false
            }
        ]
    ;(log "create-dot x: " xpos " y: " ypos " " style " " color " " (:elem dot))
    dot))


; board is apply vector on top of another apply vector.
; (def world (apply vector
;   (map (fn [_] (apply vector (map (fn [_] (ref (struct cell 0 0))) (range dim))))
;        (range dim))))
; <div class="dot levelish yellow level-5" style="top:-112px; left: 23px;"></div>
; <div class="dot levelish blue level-4" style="top:-112px; left: 68px;"></div>
(defn create-board [] 
  (vec  ; outer vector.
    (map-indexed  ; create-dot at row i, within each row, different colors.
      (fn [row x] 
        (vec    ; inner vector index is ypos, and map over a list of color.
          (map-indexed 
            (partial create-dot row) (take board-size (rand-colors)))))
      (range board-size)  ; outer index is row, board-size.
      )))


; iterate each col/x-pos of board, add missing dots to the head col, y-pos = 0.
; note we re-create all dots so we get proper x/y co-ordinate with existing color.
(defn add-missing-dots-helper 
  [col-idx col exclude-color]
  (if (= (count (remove #(:removed %) col)) board-size)
    col
    (let [missing (- board-size (count (remove #(:removed %) col)))
          new-dots (map create-dot   ; create-dot xpos ypos color
                        (repeat col-idx)
                        (repeat offscreen-dot-position)
                        (take missing (rand-colors exclude-color)))
          ; new dots at the top of the col.
          ; XXX! nwe col must be vector.
          new-col (vec (concat new-dots col))
          new-col (vec (map-indexed #(create-dot col-idx %1 (:color %2)) new-col))
         ]
      (add-dots-to-board new-dots)
      (log "add-missing-dots new-col " col-idx "  " (map #((juxt :color :dot-id :removed) %) new-col))
      new-col
    )))


; given a board and exclude color, ret a board with added dots
(defn add-missing-dots [board exclude-color]
  (vec (map-indexed
          #(add-missing-dots-helper %1 %2 exclude-color) ; col-index and a col of dots
          board)))

;; ------------------------------------------------
; dot-chain call this fn to remove dots in dot-chain. ret updated board.
(defn render-remove-dots [board dot-chain exclude-color]
  (let [dot-chain-groups  (group-by first dot-chain) ; {2 [[2 0]], 3 [[3 0]]}
        next_board (map-indexed #(render-remove-dots-row-helper
                                    (set (map last (get dot-chain-groups %1))) 
                                    %2)
                                board)
        next_board (add-missing-dots next_board exclude-color)]
    (log "render-remove-dots next board " dot-chain-groups)
    (vec next_board)))


; iterate each col, dot-chain-set contains a set of dots to be removed.
; return new col with removed dots, and add missing dots will fill it.
(defn render-remove-dots-row-helper 
  [dot-chain-set col]
  (let [dots-to-remove (keep-indexed #(if (dot-chain-set %1) %2) col)
        next_col  (keep-indexed #(if (not (dot-chain-set %1)) %2) col)]
    ;(log "render-remove-dots-row-helper " dot-chain-set)
    (doseq [dot dots-to-remove]
      (remove-dot dot))
    (vec next_col)))


; remove a dot by ($ele).remove, with some css animation.
; {:color :blue, :style {:top "-112px;", :left "23px;"}, :classname "dot levelish blue level-0"} 
(defn remove-dot [{:keys [dot-id color] :as dot}]
  (let [$elem ($ dot-id)
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
    (log "remove-dot by id " dot-id " color " color)
    (.remove ($ elem))))

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


; - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - 
; css animation for dot highlight and dot-chain lines.
; - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - 
; render dot-chain line in chain-line div.  (o)==(o), 
;   <div style="width: 23px;height: 4px;top: 166px;left: 45px;" class="line red horiz"></div>
; line origin [top, left], width=lenght, translate3d000 kicks in GPU.
(defn chain-element-templ
  [last-pos pos color]
  (let [[top1 left1] (pos->center-coord last-pos)
        [top2 left2] (pos->center-coord pos)
        length (- grid-unit-size dot-size)
        vertical (= left1 left2)
        ; css draw line, from top left, width is length
        [width height] (if vertical [4 length] [length 4])
        [off-left off-top] (if vertical [-3 11] [11 -3])
             
        style {:width (str width "px;") :height (str height "px;")
               :top (str (+ (min top1 top2) off-top) "px;")
               :left (str (+ (min left1 left2) off-left) "px;")}
        klz (str "line " (name (or color :blue)) (if (< width height) " vert" " horiz" ))
        ]
    (log "chain-element-templ " last-pos pos color " klz " klz)
    [:div {:style style :class klz}]
    ))

; render clicked dots inside div dot-highlights, (o) css animation keyframes
;   <div style="top:158px; left: 158px;" class="dot-highlight green"></div>
; dot highlight template is a div css animation keyframes expander 2 steps for .7sec
(defn dot-highlight-templ 
  [pos color]
  (let [[top left] (pos->corner-coord pos)
        ; style (str "top:" "-112 px; left: " left "px;")
        style {:top (str top "px") :left (str left "px")}
        ]
    (log "highlight templ top " top " left " left)
    [:div {:style style :class (str "dot-highlight " (name color))}]))


; get dot to remove go-loop dot-chain channel to get dots in dot-chain to remove.
(defn render-dot-chain-update 
  [board last-dot-chain dot-chain]
  (let [last-chain-length (count last-dot-chain)
        chain-length      (count dot-chain)]
    (when (and (not= last-chain-length chain-length) (pos? chain-length))
      (let [color (dot-color board (first dot-chain))
            length-diff (- chain-length last-chain-length)
            hilit (html/html (dot-highlight-templ (last dot-chain) color))
            hilitstr (html/render hilit)
            chain (html/html (chain-element-templ (last (butlast dot-chain)) (last dot-chain) color))
            chainstr (html/render chain)
            ]
        (log "render-dot-chain-update chain-length " chain-length " diff " length-diff 
             "first " (first dot-chain) " last " (last dot-chain) " color " color
             " hilit " hilit " dot-chain " dot-chain)
        (if (< 1 chain-length)
          (if (pos? length-diff)
            ; render dot-chain line in chain-line div.  (o)==(o)
            (append ($ ".dots-game .chain-line") chainstr)
            (.remove (.last ($ ".dots-game .chain-line .line")))) ; remove 
          (inner ($ ".dots-game .chain-line") ""))
        ; render (o) within div dot-highlights
        ; (append ($ ".dots-game .dot-highlights") "<b>hello</b>")
        ; (log "html returns " hilit " " hilitstr)
        (append ($ ".dots-game .dot-highlights") hilitstr)
                 ; "<div style=\"top:248px; left: 248px;\" class=\"dot-highlight blue\"></div>")
      ))
    ))


; whether cur-dot is follow prev-dot based on color, to populate dot-chain.
(defn dot-follows? [board prev-dot cur-dot]
  (and (not= prev-dot cur-dot)
       (or (nil? prev-dot)
           (and
            (= (dot-color board prev-dot) (dot-color board cur-dot))
            (= 1 (apply + (mapv (comp abs -) cur-dot prev-dot)))))))

; new dot added to dot-chain at dot-pos, insert into dot-chain.
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



