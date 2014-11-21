(ns leiningen.auto-release
  (:refer-clojure :exclude [merge resolve])
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [leiningen.core.eval :as eval]
   [leiningen.core.main :as main]))

(def repo-ensured? (atom nil))
(def last-branch (atom nil))

(defn branches [{:keys [root]} & opts]
  (let [{:keys [out] :as cmd}
        (apply shell/sh (concat ["git" "branch"] opts [:dir root]))]
    (->> (java.io.StringReader. out)
         (io/reader)
         (line-seq)
         (map #(if-let [[[_ current]] (re-seq #"^\* ([\w\-]+)" %)]
                 {:current current}
                 (string/trim %))))))

(defn current-branch [project]
  (->> (branches project)
       (keep :current)
       (first)))

(defn branch-exists? [project branch]
  (->> (branches project)
       (map #(or (:current %) %))
       (some #{branch})))

(defn remote-exists? [project branch]
  (->> (branches project "--all")
       (map #(or (:current %) %))
       (some #{(format "remotes/%s" branch)})))

(defn fetch-all [{:keys [root]}]
  (let [{:keys [out exit] :as cmd} (shell/sh "git" "fetch" "--all" :dir root)]
    (= 0 exit)))

(defn remote-update [{:keys [root]}]
  (let [{:keys [out exit] :as cmd} (shell/sh "git" "remote" "update" :dir root)]
    (= 0 exit)))

(defn up-to-date? [{:keys [root]} branch]
  (let [{:keys [out exit] :as cmd}
        (shell/sh "git"
                  "rev-list"
                  (format "%s...origin/%s" branch branch)
                  "--count"
                  :dir root)]
    (= "0\n" out)))

(defn tracking? [project branch]
  (and (branch-exists? project branch)
       (remote-exists? project (format "origin/%s" branch))))

(defn latest-tag [{:keys [root]}]
  (let [{:keys [out] :as cmd} (shell/sh "git" "tag" :dir root)]
    (->> (java.io.StringReader. out)
         (io/reader)
         (line-seq)
         (map #(re-seq #"(\d+)\.(\d+)\.(\d+)$" %))
         (map first)
         (sort-by (fn [[ver maj min patch]]
                    [(Integer. maj) (Integer. min) (Integer. patch)]))
         (map first)
         (last))))

(defn ^{:ensure-msg "Readme does not contain correct version"}
  readme-version? [{:keys [root group name] :as project}]
  (let [readme (io/file root "readme.md")
        symbol (symbol group name)
        artifact-str (format "[%s \"%s\"]" symbol (latest-tag project))]
    (->> (io/reader readme)
         (line-seq)
         (filter #(.contains % artifact-str))
         (seq))))

(defn ^{:ensure #'readme-version?}
  update-readme-version [{:keys [root group name version] :as project}]
  (let [readme (io/file root "readme.md")
        symbol (symbol group name)
        artifact-str (format "[%s \"%s\"]" symbol (latest-tag project))
        new-artifact-str (format "[%s \"%s\"]" symbol version)
        tmp (java.io.File/createTempFile "readme.md" ".tmp")]
    (spit tmp "")
    (->> (io/reader readme)
         (line-seq)
         (map #(spit tmp
                     (str (.replace % artifact-str new-artifact-str) "\n")
                     :append true))
         (doall))
    (io/copy tmp readme)))

(def resolve (partial ns-resolve 'leiningen.auto-release))

(defn ensure-repo [{:keys [root release-tasks] :as project}]
  (try
    (doseq [ensure-task (->> release-tasks
                             (filter (comp #{"auto-release"} first))
                             (keep (comp :ensure meta resolve symbol second)))]
      (assert (ensure-task project) (:ensure-msg (meta ensure-task))))
    (assert (= "develop" (current-branch project)) "Release must be started on branch `develop`")
    (assert (remote-update project) "Remote update failed")
    (assert (up-to-date? project "develop") "Branch `develop` not up to date")
    (assert (or (not (tracking? project "master"))
                (up-to-date? project "master"))
            "Branch `master` not up to date")
    (catch AssertionError e
      (println e)
      (System/exit 1))))

(defn checkout [{:keys [root] :as project} branch & opts]
  (when-let [current (current-branch project)]
    (printf "Branch: %s\n" (reset! last-branch current)))
  (binding [eval/*dir* root]
    (apply eval/sh (concat ["git" "checkout"] opts [branch]))))

(defn checkout-latest-tag [project]
  (checkout project (format "v%s" (latest-tag project))))

(defn checkout-last-branch [project]
  (checkout project @last-branch))

(defn push [project & args]
  (binding [eval/*dir* (:root project)]
    (apply eval/sh "git" "push" args)))

(defn pull [project]
  (binding [eval/*dir* (:root project)]
    (eval/sh "git" "pull")))

(defn checkout-gh-pages-branch [{:keys [root] :as project}]
  (if (branch-exists? project "gh-pages")
    (do
      (checkout project "gh-pages")
      (pull project)
      {:existed? true})
    (do
      (checkout project "gh-pages" "--orphan")
      (let [files (remove #(.startsWith (.getName %) ".")
                          (.listFiles (io/file root)))]
        (doseq [file (filter #(.isDirectory %) files)]
          (try
            (shell/sh "rm" "-r" (.getCanonicalPath file))
            (catch Exception _))
          (try
            (shell/sh "git" "rm" "-r" "--cached" (.getCanonicalPath file))
            (catch Exception _)))
        (doseq [file (remove #(.isDirectory %) files)]
          (try
            (shell/sh "rm" (.getCanonicalPath file))
            (catch Exception _))
          (try
            (shell/sh "git" "rm" "--cached" (.getCanonicalPath file))
            (catch Exception _))))
      {:existed? false})))

(defn add [project & args]
  (binding [eval/*dir* (:root project)]
    (apply eval/sh "git" "add" args)))

(defn commit [project message & opts]
  (binding [eval/*dir* (:root project)]
    (apply eval/sh (concat ["git" "commit"] opts ["-m" message]))))

(defn merge-no-ff [{:keys [root]} branch]
  (binding [eval/*dir* root]
    (eval/sh "git" "merge" "--no-ff" branch "--no-edit")))

(defn merge [{:keys [root]} branch]
  (binding [eval/*dir* root]
    (eval/sh "git" "merge" branch "--no-edit")))

(defn tag [{:keys [root version]} & [prefix]]
  (binding [eval/*dir* root]
    (let [tag (if prefix
                (str prefix version)
                version)]
      (eval/sh "git" "tag" tag "-m" (str "Release " version)))))

(defn commit-log [{:keys [root]} last-version]
  (let [{:keys [out] :as cmd}
        (shell/sh "git" "--no-pager" "log" "--format=%H" (format "v%s.." last-version)
                  :dir root)
        {:keys [out] :as cmd}
        (shell/sh "git" "log" "--pretty=changelog" "--stdin" "--no-walk"
                  :dir root :in out)]
    (->> (java.io.StringReader. out)
         (io/reader)
         (line-seq)
         (remove #(or (empty? %)
                      (re-seq #"^- Bump to" %)
                      (re-seq #"^- Prepare release" %)
                      (re-seq #"^- [Cc]heck" %)
                      (re-seq #"^- Release " %)
                      (re-seq #"^- Version " %)
                      (re-seq #"^- Merge branch " %)
                      (re-seq #"^- Merged in " %))))))

(defn update-release-notes [{:keys [root version] :as project}]
  (println "Updating release notes with commit log")
  (binding [eval/*dir* root]
    (let [file (io/file root "ReleaseNotes.md")
          tmp (java.io.File/createTempFile "release-notes" ".tmp")]
      (println (format "## v%s\n\n" version))
      (spit tmp (format "## v%s\n\n" version))
      (doseq [line (commit-log project (latest-tag project))]
        (println line)
        (spit tmp (str line \newline) :append true))
      (spit tmp "\n" :append true)
      (if (.exists file)
        (with-open [r (io/reader file)]
          (doseq [line (line-seq r)]
            (spit tmp (str line \newline) :append true)))
        (eval/sh "git" "add" "ReleaseNotes.md"))
      (io/copy tmp file))))

(defn ^{:ensure-msg "Branch `gh-pages` not up to date"} ensure-gh-pages
  [project]
  (up-to-date? project "gh-pages"))

(defn ^{:ensure #'ensure-gh-pages} update-marginalia-gh-pages
  [{:keys [root] :as project}]
  (let [doc (io/file root "docs/uberdoc.html")
        text (slurp doc)
        _ (.delete doc)
        {:keys [existed?]} (checkout-gh-pages-branch project)
        uberdoc (io/file root "uberdoc.html")
        latest-tag (latest-tag project)]
    (spit uberdoc text)
    (io/copy uberdoc (io/file root (format "%s.html" latest-tag)))
    (add project "*.html")
    (commit project (format "Regen marginalia docs: v%s" latest-tag))
    (if existed?
      (push project)
      (push project "-u" "origin" "gh-pages"))
    (checkout-last-branch project)))

(defn- not-found [subtask]
  (partial #'main/task-not-found (str "auto-release " subtask)))

(defn ^{:subtasks [#'checkout #'checkout-latest-tag #'merge-no-ff #'merge #'tag
                   #'update-readme-version #'update-release-notes
                   #'update-marginalia-gh-pages]}
  auto-release
  "Interact with the version control system."
  [project subtask & args]
  (when-not @repo-ensured?
    (ensure-repo project)
    (reset! repo-ensured? true))
  (let [subtasks (:subtasks (meta #'auto-release) {})
        [subtask-var] (filter #(= subtask (name (:name (meta %)))) subtasks)]
    (apply (or subtask-var (not-found subtask)) project args)))
