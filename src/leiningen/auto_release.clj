(ns leiningen.auto-release
  (:refer-clojure :exclude [merge resolve])
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [leiningen.core.eval :as eval]
   [leiningen.core.main :as main]))

(def repo-ensured? (atom nil))

(defn current-branch [{:keys [root]}]
  (let [{:keys [out] :as cmd} (shell/sh "git" "branch" :dir root)]
    (->> (java.io.StringReader. out)
         (io/reader)
         (line-seq)
         (map #(re-seq #"^\* (\w+)" %))
         (remove nil?)
         (ffirst)
         (last))))

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
    (assert (= "develop" (current-branch project)) "Not on branch `develop`")
    (assert (remote-update project) "Remote update failed")
    (assert (up-to-date? project "develop") "Branch `develop` not up to date")
    (assert (up-to-date? project "master") "Branch `master` not up to date")
    (catch AssertionError e
      (println e)
      (System/exit 1))))

(defn checkout [{:keys [root]} branch]
  (binding [eval/*dir* root]
    (eval/sh "git" "checkout" branch)))

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
                      (re-seq #"^- Merge branch " %))))))

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

(defn- not-found [subtask]
  (partial #'main/task-not-found (str "auto-release " subtask)))

(defn ^{:subtasks [#'checkout #'merge-no-ff #'merge #'tag
                   #'update-readme-version #'update-release-notes]}
  auto-release
  "Interact with the version control system."
  [project subtask & args]
  (when-not @repo-ensured?
    (ensure-repo project)
    (reset! repo-ensured? true))
  (let [subtasks (:subtasks (meta #'auto-release) {})
        [subtask-var] (filter #(= subtask (name (:name (meta %)))) subtasks)]
    (apply (or subtask-var (not-found subtask)) project args)))
