# dots with OM

The final frontier is here, `om` integrates `React.js` and `core.async` and acheieves data flow programming with scale.

We demonstrate the power of `om` with an implemenation of Dotster Game. 

The original Dotster game is implemented in ClojureScript using the Core.async library. [See the blog post explaining the game](http://rigsomelight.com/2013/08/12/clojurescript-core-async-dots-game.html)


## Cljsbuild tutorial

In advanced optimizations, all symbols and function names got renamed/munges. This includes function names that reference functions in dependency libs. so if you call $.ajax() in the code, ajax() will be munges to something else that will throw you a js error if jquery is not compiled by Goog closure compiler together.

Solution: 

1. include dependency libs within your compilation set, so all names got munges together. However, most libs are not Google Closure compatible.

  :libs ["libs/foobar.js"] ; js must include goog.provide('foobar');
  :foreign-libs [{:file "http://foo.com/foobar.js"
                  :provides ["foo.bar"]}]

2. To reference a variable declared externally from within your code, you must provide the Google Closure compiler with an "extern file", a .js file defining javascript variables which will not be munged. By doing this, the lib is included in the external JS context, rather than in your compilation set, and all symbols and function names in externs , refered by any file from anywhere, will not be munged.

To prevent munging, You can either create an externs file manually, or if you're willing to put up with some warnings, you can use the library as its own externs file, as is demonstrated below.

    {:optimizations :advanced
     :externs ["lib/raphael-min.js"]
     :output-to "helloworld.js"
    }

To find externs files for popular js libs, go to closure-compiler repository. For example, you can download jquery externs from there.

  https://code.google.com/p/closure-compiler/source/browse/#git%2Fcontrib%2Fexterns


For more information, read 

  http://hugoduncan.org/post/clojurescript-libs-with-js-dependencies/

  https://github.com/emezeske/lein-cljsbuild/blob/1.0.2/sample.project.clj

  http://lukevanderhart.com/2011/09/30/using-javascript-and-clojurescript.html

  https://github.com/magomimmo/modern-cljs/tree/master/doc

## Build

Specify Clojure, ClojureScript, and core.async and om dependencies in project.clj. You may need to tweak the project.clj so that all versions compatible.

The `project.clj` contains build for todo and dots, both in `:optimizations :advanced`.

Important Note here is that, during cljsbuild, it will put all intermediate result into `target//cljsbuild-compiler-X` directory. You need to clean up the directory when switching build target, otherwise you will get weird error in the final app.js. set 

    :output-dir "target/my-compiler-output-"


run `lein cljsbuild once todo` to build todo and view with index.html.
run `lein cljsbuild once dots` to build dots and view with dots.html.

The order of variables in cljs matters. Need to use declare before use. Lesson learned from swap! on undefined app-state.

## License

Copyright © 2014

Distributed under the Eclipse Public License, the same as Clojure.
