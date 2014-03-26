(defproject dotsterom "0.1.0-SNAPSHOT"
  :description "dotster game using om!"
  :url "https://github.com/life0fun/omdotster"

  :jvm-opts ^:replace ["-Xmx1g" "-server"]

  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/clojurescript "0.0-2173"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                 [secretary "0.4.0"]
                 [om "0.5.3"]
                 [crate "0.2.4"]
                 [jayq "2.4.0"]
                 [com.cemerick/piggieback "0.0.5"]]

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
             {:id "release"
              :source-paths ["src/dots"]
              :compiler {
                :output-to "dots.js"
                :optimizations :advanced
                :elide-asserts true
                :pretty-print false
                :output-wrapper false
                :preamble ["react/react.min.js"]
                :externs ["react/externs/react.js" 
                          "resources/public/js/externs/jquery-1.9.js"]}}]})
