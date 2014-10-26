(ns leiningen.auto-release
  (:require
   [clojure.java.io :as io]
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
  
  )

;; (let [file (io/file "." "ReleaseNotes.md")
;;       text (if (.exists file) (slurp file) "")]
;;   text
;;   )
