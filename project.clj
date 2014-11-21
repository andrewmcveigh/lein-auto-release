(defproject com.andrewmcveigh/lein-auto-release "0.1.9"
  :description "A Leiningen plugin for automated releases"
  :url "https://github.com/andrewmcveigh/lein-auto-release"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :eval-in-leiningen true
  :release-tasks [["vcs" "assert-committed"]
                  ["auto-release" "checkout" "master"]
                  ["auto-release" "merge-no-ff" "develop"]
                  ["change" "version"
                   "leiningen.release/bump-version" "release"]
                  ["auto-release" "update-release-notes"]
                  ["auto-release" "update-readme-version"]
                  ["vcs" "commit"]
                  ["vcs" "tag" "v"]
                  ["deploy" "clojars"]
                  ["vcs" "push"]
                  ["auto-release" "checkout" "develop"]
                  ["auto-release" "merge" "master"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]])
