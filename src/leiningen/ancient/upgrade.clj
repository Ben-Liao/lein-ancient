(ns ^{ :doc "Rewrite project.clj to include latest versions of dependencies."
       :author "Yannick Scherer" }
  leiningen.ancient.upgrade
  (:require [leiningen.ancient.utils.test :as t]
            [leiningen.ancient.utils.projects :refer :all]
            [leiningen.ancient.utils.cli :refer :all]
            [leiningen.ancient.utils.io :refer :all]
            [leiningen.ancient.check :as c]
            [leiningen.core.project :as prj]
            [leiningen.core.main :as main]
            [ancient-clj.verbose :refer :all]
            [rewrite-clj.zip :as z]
            [clojure.java.io :as io :only [file writer]])
  (:import java.io.File))

;; ## Concept
;;
;; We use Leiningen's built-in functions to read the project map from a file,
;; and rewrite-clj's zipper to access said file. We can use lein-ancient's existing
;; check functions to find the artifacts that need updating and rewrite-clj to
;; create the updated project map.

;; ## Prompt

(defn- prompt-for-upgrade!
  "If the `:interactive` flag in the given settings map is set, this function will ask the
   user (on stdout/stdin) whether he wants to upgrade the given artifact and return a boolean
   value indicating the user's choice."
  [settings artifact]
  (when (:latest artifact)
    (when (:interactive settings) (main/info))
    (c/print-outdated-message artifact)
    (or (not (:interactive settings))
        (prompt "Do you want to upgrade?"))))

(defn- filter-artifacts-with-prompt!
  "Given a seq of artifacts (with the ':latest' key set), retain only those
   that should be upgraded. If ':interactive' is set in the settings map
   this is decided by prompting the user."
  [settings artifacts]
  (filter
    (partial prompt-for-upgrade! settings)
    artifacts))

;; ## Zipper Helpers

(defn- move-to-value-or-index
  "This goes to the value preceded by a given keyword or the node
   at a given position right of the current one."
  [zloc p]
  (cond (not zloc) nil
        (integer? p) (nth
                       (->> (iterate z/right zloc)
                            (remove #(= (z/tag %) :uneval)))
                       p)
        (keyword? p) (when-let [floc (z/find-value zloc p)]
                       (when-let [floc (z/right floc)]
                         (z/down floc)))
        :else nil))

(defn- move-to-path
  "Given a path seq (consisting of map keys and indices) move to a zipper location
   represented by the given path."
  [zloc ps]
  (reduce move-to-value-or-index zloc ps))

(defn- read-project-zipper!
  "Read rewrite-clj zipper from project file."
  [path]
  (try
    (when-let [loc (z/of-file path)]
      (when-let [loc (z/find-value loc z/next 'defproject)]
        (z/up loc)))
    (catch Exception ex
      (main/info (red "ERROR:") "could not create zipper from file at:" path)
      (main/info (red "ERROR:") (.getMessage ex))
      nil)))

(defn read-profiles-zipper!
  [path]
  "Read rewrite-clj zipper from profiles file."
  (try
    (when-let [loc (z/of-file path)]
      (z/find-tag loc z/next :map))
    (catch Exception ex
      (main/info (red "ERROR:") "could not create zipper from file at:" path)
      (main/info (red "ERROR:") (.getMessage ex))
      nil)))

(defn- write-zipper!
  "Write zipper back to file. Returns true if everything worked correctly."
  [^String path zloc settings]
  (try
    (do
      (if (:print settings)
        (do
          (main/info)
          (z/print-root zloc)
          (main/info))
        (binding [*out* (io/writer path)]
          (z/print-root zloc)
          (.flush ^java.io.Writer *out*)))
      true)
    (catch Exception ex
      (main/info (red "An error occured while writing the generated data:") (.getMessage ex)))))

;; ## Zipper Update

(defn- upgrade-artifact-node!
  "Use the path stored in the artifact map to find it in the zipper and
   upgrade its version."
  [map-loc settings {:keys [group-id artifact-id version latest] :as artifact}]
  (or
    (when-let [zloc (when map-loc (z/down map-loc))]
      (when-let [path (artifact-path artifact)]
        (when-let [artifact-loc (move-to-path zloc path)]
          (when (z/vector? artifact-loc)
            (let [loc (z/down artifact-loc)]
              (if-let [version-loc (z/right loc)]
                (z/replace version-loc (first latest))
                (z/insert-right loc (first latest))))))))
    map-loc))

(defn- upgrade-artifact-map!
  "Given a zipper residing on a map (or map-like structure, e.g. defproject s-expr) and
   a seq of outdated artifacts (containing the `:latest` key with the version to upgrade to),
   upgrade the zipper to contain the respective versions."
  [map-loc settings artifacts]
  (reduce
    (fn [map-loc artifact]
      (z/subedit-> map-loc (upgrade-artifact-node! settings artifact)))
    map-loc artifacts))

(defn upgrade-artifact-file!
  "Upgrade an artifact file. We need:
   - a function that produces the data contained in the file
   - two collect functions that retrieve repositories and artifact maps from that data
   - a function that creates a rewrite-clj zipper from the file
   These parts depend on whether you want to read a project or profiles file.
   Behaviour can be modified by supplying different settings maps.

   This will return true if the upgrade was successful or no changes were made; nil otherwise."
  [read-map-fn collect-repo-fn collect-artifact-fn read-zipper-fn project settings path]
  (when-let [artifact-map (read-map-fn path)]
    (let [repos (collect-repo-fn project artifact-map)
          artifacts (collect-artifact-fn artifact-map settings)]
      (with-output-settings settings
        (if-let [outdated (seq
                            (->> artifacts
                              (c/get-outdated-artifacts! repos settings)
                              (filter-artifacts-with-prompt! settings)))]
          (when-let [map-loc (read-zipper-fn path)]
            (when-let [new-loc (upgrade-artifact-map! map-loc settings outdated)]
              (when (:interactive settings) (main/info))
              (main/info (count outdated)
                       (if (= (count outdated) 1)
                         "artifact was"
                         "artifacts were")
                       "upgraded.")
              (write-zipper! path new-loc settings)))
          (do (main/info "Nothing was upgraded.") ::nothing))))))

;; ## Upgrade Mechanisms

(def ^:private upgrade-project-file!*
  "Upgrade the project file (containing 'defproject') at the given path using the given
   settings."
  (partial upgrade-artifact-file!
           read-project-map!
           #(collect-repositories %2)
           collect-artifacts
           read-project-zipper!))

(def ^:private upgrade-profiles-file!*
  (partial upgrade-artifact-file!
           read-profiles-map!
           collect-profiles-repositories
           collect-profiles-artifacts
           read-profiles-zipper!))

;; ## Upgrade w/ Backup

(defn- with-backup
  "Wraps a given function to produce/restore backup files. Returns true
   if upgrade was successful or no changes were written to disk. Will not
   create backups if `:print` is specified."
  [upgrade-fn]
  (fn [project settings path]
    (with-output-settings settings
      (let [^File f (io/file path)
            ^String path (.getCanonicalPath f)]
        (verbose "Upgrading artifacts in: " path)
        (if (:print settings)
          (upgrade-fn project settings path)
          (when-let [backup (create-backup-file! f settings)]
            (if (upgrade-fn project settings path)
              (do (delete-backup-file! backup) true)
              (do (replace-with-backup! f backup) nil))))))))

;; ## Tests

(defn- run-regression-tests!
  [path]
  (main/info)
  (when-let [project (prj/read path)]
    (t/run-tests! project)))

(defn- with-tests
  "Wrap a given function to run tests when new data was written to disk. Returns true
   if tests succeeded, false otherwise."
  [upgrade-fn]
  (fn [project settings path]
    (when-let [r (upgrade-fn project settings path)]
      (or (= r ::nothing)
          (not (:tests settings))
          (:print settings)
          (run-regression-tests! path)))))

(defn- with-abort
  "Wrap a given function to call Leiningen's 'abort' if upgrading failed."
  [upgrade-fn]
  (fn [& args]
    (when-not (apply upgrade-fn args)
      (main/abort "Upgrade failed."))
    true))

;; Combine

(def upgrade-project-file!
  "Run upgrade on the given project file using the given settings."
  (-> upgrade-project-file!*
    with-tests
    with-backup
    with-abort))

(def upgrade-profiles-file!
  "Run upgrade on the given profiles file using the given settings."
  (-> upgrade-profiles-file!*
    with-backup
    with-abort))

;; ## Wrappers

(defn- upgrade-file!
  "Upgrade file at the given location, either 'project.clj' or 'profiles.clj'."
  [project ^java.io.File f settings]
  (let [path (.getPath f)]
    (when (.isFile f)
      (when (:recursive settings) (main/info "--" path))
      (cond (= (.getName f) "project.clj") (upgrade-project-file! project settings path)
            (= (.getName f) "profiles.clj") (upgrade-profiles-file! project settings path)
            :else (main/abort "ERROR: Can only upgrade either 'project.clj' or 'profiles.clj'."))
      (when (:recursive settings) (main/info)))))

(defn- upgrade-directory!
  "Upgrade directory, possibly recursively."
  [project ^java.io.File path settings]
  (let [top-file (io/file path "project.clj")]
    (cond (:recursive settings) (let [project-files (find-files-recursive! path "project.clj")]
                                  (doseq [^java.io.File project-file project-files]
                                    (upgrade-file! project project-file settings)))
          (.isFile top-file) (upgrade-file! project top-file settings)
          :else (main/abort "No file at:" (.getPath top-file)))))

(defn- upgrade-path!
  "Upgrade file/directory at the given path."
  [project path settings]
  (let [^java.io.File f (io/file path)]
    (cond (.isFile f) (upgrade-file! project f (assoc settings :recursive false))
          (.isDirectory f) (upgrade-directory! project f settings)
          :else (main/abort "No such file or directory:" path))))

;; ## Task

(defn run-upgrade-task!
  "Run artifact upgrade on project file."
  [{:keys [root] :as project} args]
  (if (exists? (last args))
    (upgrade-path! project (last args) (parse-cli (butlast args)))
    (upgrade-path! project (or root ".") (parse-cli args))))

(defn run-upgrade-profiles-task!
  "Run plugin upgrade on global profiles."
  [project args]
  (let [profiles-file (io/file (System/getProperty "user.home") ".lein" "profiles.clj")
        settings (-> (parse-cli args)
                   (assoc :plugins true))]
    (upgrade-profiles-file! project settings profiles-file)))
