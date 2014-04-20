(defproject dotsterom "0.1.0-SNAPSHOT"
  :description "dotster game using om!"
  :url "https://github.com/life0fun/omdotster"

  :jvm-opts ^:replace ["-Xmx1g" "-server"]

  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/clojurescript "0.0-2173"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                 ;[com.facebook/react "0.9.0.1"] contains react/externs/react.js
                 [om "0.5.3"]
                 [secretary "0.4.0"]
                 [sablono "0.2.15"]
                 [jayq "2.4.0"]
                 [com.cemerick/piggieback "0.0.5"]
                ]

  :plugins [[lein-cljsbuild "1.0.2"]]
  :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
  :cljsbuild { 
    :builds [{:id "dev"
              :source-paths ["src/dots"]
              :compiler {
                :output-to "dots.js"
                :output-dir "out"
                :optimizations :none
                :source-map true}}
             {:id "todo"
              :source-paths ["todosrc"]
              :compiler {
                :output-to "app.js"
                :optimizations :advanced
                :elide-asserts true
                :pretty-print false
                :output-wrapper false
                :preamble ["react/react.min.js"]
                :externs ["react/externs/react.js" 
                          ;"resources/public/js/externs/jquery-1.9.js"
                          ]
              }}
            {:id "dots"
              :source-paths ["src"]
              :compiler {
                :output-to "dots.js"
                :optimizations :advanced
                :elide-asserts true
                :pretty-print false
                :output-wrapper false
                :preamble ["react/react.min.js"]
                :externs ["react/externs/react.js" 
                          "resources/public/js/externs/jquery-1.9.js"
                          ]
              }}]
    })
