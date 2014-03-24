# dots with OM

Dotster is a game implemented in ClojureScript using the Core.async library.

[See the blog post explaining the game](http://rigsomelight.com/2013/08/12/clojurescript-core-async-dots-game.html)


## The Improvement

The dotsters directory contains bug fixes and major code refactors that helps beginner to understand core.async patterns.

The om directory contains the re-implements using `om`. `om` is the final frontier of front-end, as it builds on top of `react.js` and using core.async for inter-component communication. With react.js, we build UI fragement using functions. With core.async, communication is based on data and work can be split and distributed across webworkers.


## License

Copyright © 2014

Distributed under the Eclipse Public License, the same as Clojure.
