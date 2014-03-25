# dots with OM

The final frontier is here, `om` integrates `React.js` and `core.async` and acheieves data flow programming with scale.

We demonstrate the power of `om` with an implemenation of Dotster Game. 

The original Dotster game is implemented in ClojureScript using the Core.async library. [See the blog post explaining the game](http://rigsomelight.com/2013/08/12/clojurescript-core-async-dots-game.html)


## Running

Specify Clojure, ClojureScript, and core.async and om dependencies in project.clj. You may need to tweak the project.clj so that all versions compatible.

Install Om by cloning and running lein install. Then clone this repo and 

run `lein cljsbuild once release` to build. 
Open index.html in your favorite browser.

## License

Copyright © 2014

Distributed under the Eclipse Public License, the same as Clojure.
