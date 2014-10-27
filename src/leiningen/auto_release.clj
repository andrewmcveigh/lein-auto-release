(ns leiningen.auto-release
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [leiningen.core.eval :as eval]))

(defn checkout [{:keys [root]} branch]
  (binding [eval/*dir* root]
    (eval/sh "git" "checkout" branch)))

(defn merge-no-ff [{:keys [root]} branch]
  (binding [eval/*dir* root]
    (eval/sh "git" "merge" "--no-ff" branch "--no-edit")))

(defn merge [{:keys [root]} branch]
  (binding [eval/*dir* root]
    (eval/sh "git" "merge" branch "--no-edit")))

(defn update-release-notes [{:keys [root version]}]
  (binding [eval/*dir* root]
    (let [file (io/file root "ReleaseNotes.md")
          tmp (java.io.File/createTempFile "release-notes" ".tmp")]
      (prn tmp)
      (spit tmp (format "## v%s\n\n" version))
      ;; (with-open [r (io/reader file)]
      ;;   (doseq [line (line-seq r)]
      ;;     (prn line)
      ;;     (spit tmp (str line \newline) :append true)))
      (println (slurp tmp))))
  ) 

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

;; (update-release-notes {:root "/Users/andrewmcveigh/Projects/com.andrewmcveigh/refdb" :version "0.1.0"})
;; (latest-tag {:root "/Users/andrewmcveigh/Projects/com.andrewmcveigh/refdb"})
(commit-log {:root "/Users/andrewmcveigh/Projects/com.andrewmcveigh/refdb"} "0.5.0")
