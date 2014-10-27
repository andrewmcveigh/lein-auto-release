(ns leiningen.auto-release
  (:refer-clojure :exclude [merge])
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [leiningen.core.eval :as eval]
   [leiningen.core.main :as main]))

(defn checkout [{:keys [root]} branch]
  (binding [eval/*dir* root]
    (eval/sh "git" "checkout" branch)))

(defn merge-no-ff [{:keys [root]} branch]
  (binding [eval/*dir* root]
    (eval/sh "git" "merge" "--no-ff" branch "--no-edit")))

(defn merge [{:keys [root]} branch]
  (binding [eval/*dir* root]
    (eval/sh "git" "merge" branch "--no-edit")))

(defn latest-tag [{:keys [root]}]
  (let [{:keys [out] :as cmd} (shell/sh "git" "tag" :dir root)]
    (->> (java.io.StringReader. out)
         (io/reader)
         (line-seq)
         (map #(re-seq #"\d+\.\d+\.\d+$" %))
         (map first)
         (sort)
         (last))))

(defn current-branch [{:keys [root]}]
  (let [{:keys [out] :as cmd} (shell/sh "git" "branch" :dir root)]
    (->> (java.io.StringReader. out)
         (io/reader)
         (line-seq)
         (map #(re-seq #"^\* (\w+)" %))
         (remove nil?)
         (ffirst)
         (last))))

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
                      (re-seq #"^- Release " %)
                      (re-seq #"^- Merge branch " %))))))

(defn update-release-notes [{:keys [root version] :as project}]
  (binding [eval/*dir* root]
    (let [file (io/file root "ReleaseNotes.md")
          tmp (java.io.File/createTempFile "release-notes" ".tmp")]
      (spit tmp (format "## v%s\n\n" version))
      (doseq [line (commit-log project (latest-tag project))]
        (spit tmp line :append true))
      (when (.exists file)
        (with-open [r (io/reader file)]
          (doseq [line (line-seq r)]
            (spit tmp (str line \newline) :append true))))
      (io/copy tmp file))))

(defn- not-found [subtask]
  (partial #'main/task-not-found (str "auto-release " subtask)))

(defn ^{:subtasks [#'checkout #'merge-no-ff #'merge #'update-release-notes]}
  auto-release
  "Interact with the version control system."
  [project subtask & args]
  (let [subtasks (:subtasks (meta #'auto-release) {})
        [subtask-var] (filter #(= subtask (name (:name (meta %)))) subtasks)]
    (apply (or subtask-var (not-found subtask)) project args)))
