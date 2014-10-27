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

(defn tag [{:keys [root version]} & [prefix]]
  (binding [eval/*dir* root]
    (let [tag (if prefix
                (str prefix version)
                version)]
      (eval/sh "git" "tag" tag "-m" (str "Release " version)))))

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
                      (re-seq #"^- Prepare release" %)
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
        (spit tmp line :append true))
      (if (.exists file)
        (with-open [r (io/reader file)]
          (doseq [line (line-seq r)]
            (spit tmp (str line \newline) :append true)))
        (eval/sh "git" "add" "ReleaseNotes.md"))
      (io/copy tmp file))))

(defn- not-found [subtask]
  (partial #'main/task-not-found (str "auto-release " subtask)))

(defn ^{:subtasks [#'checkout #'merge-no-ff #'merge #'tag #'update-release-notes]}
  auto-release
  "Interact with the version control system."
  [project subtask & args]
  (let [subtasks (:subtasks (meta #'auto-release) {})
        [subtask-var] (filter #(= subtask (name (:name (meta %)))) subtasks)]
    (apply (or subtask-var (not-found subtask)) project args)))
