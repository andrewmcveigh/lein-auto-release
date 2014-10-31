# lein-auto-release

A Leiningen plugin to handle automatic merging of branches in the
lein release process. Includes other git utils.

## Usage

Put `[com.andrewmcveigh/lein-auto-release "0.1.4"]` into the
`:plugins` vector of your project.clj.

```clojure
(defproject
  ...

  :release-tasks [["auto-release" "checkout" "master"]
                  ["auto-release" "merge-no-ff" "develop"]
                  ["vcs" "assert-committed"]
                  ["change" "version"
                   "leiningen.release/bump-version" "release"]
                  ["auto-release" "update-release-notes"]
                  ["vcs" "commit"]
                  ["vcs" "tag" "v"]
                  ["deploy" "clojars"]
                  ["vcs" "push"]
                  ["auto-release" "checkout" "develop"]
                  ["auto-release" "merge" "master"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]])
```

## License

Copyright © 2014 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
