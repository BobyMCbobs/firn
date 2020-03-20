(ns firn.core
  (:require [cheshire.core :as json]
            [clojure.java.shell :as sh]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [firn.config :as config]
            [firn.layout :as layout]
            [me.raynes.fs :as fs])
  (:gen-class))


;; -------------------------- helpers
;; (defn- build-file-outpath
;;   "For the current file, get the path for everything after the files-dirname"
;;   [{:keys [curr-file files-dirname out-dir] :as config}]
;;   (let [curr-file-path           (-> curr-file :original .getPath)
;;         file-path-split          (s/split curr-file-path #"/")
;;         files-dir-to-end-of-file (drop-while #(not= files-dirname %) file-path-split)]))


(defn- build-file-outpath
  "For the current file, build it's output filename
  based on out-dir, the path of the file (it could be several layers deep)
  Returns the file name as a string."
  [{:keys [out-dirname files-dirname curr-file]}]
  (let [curr-file-path (-> curr-file :original .getPath)
        out-comb       (str files-dirname "/" out-dirname)]
    (-> curr-file-path
        (s/replace #"\.org" ".html")
        (s/replace (re-pattern files-dirname) (str out-comb))))) ;; < str to make linter happy.


;; Le grandiose --------------------------------

(defn setup
  "Creates output directory for files and sets up starting
  config file that gets passed through all functions
  Also, moves your `media folder` into _site. TODO - make configurable..."
  [config]

  (println "Setup: Making _site output.")
  (fs/mkdir (config :out-dir))

  (println "Setup: Copying root media into out media")
  (fs/copy-dir (config :media-dir) (config :out-media-dir))

  (-> config layout/get-layouts-and-partials))

(defn parse!
  "Shells out to the rust binary to parse the org-mode file."
  [file-str]
  (let [res (sh/sh "./src/parser/target/debug/parser" file-str)]
    (if-not (= (res :exit) 0)
      (prn "Orgize failed to parse file." file-str res)
      (res :out))))

(defn get-files

  ;; TODO -  maybe better belongs in setup function?
  "Returns a list of files as Java objects. Filters out all non `.org` files."
  [{:keys [files-dir] :as config}]
  (println "Getting org files...")
  (conj config {:org-files (fs/find-files files-dir #"^.*\.(org)$")}))

(defn read-file
  "Pulls :curr-file from config > parses > put into config with new vals"
  [config]
  (let [file-orig   (-> config :curr-file :original)
        file-parsed (->> file-orig slurp parse!)
        file-name   (-> file-orig .getName (s/split #"\.") (first))]
    (config/update-curr-file config {:name file-name :as-json file-parsed})))

(defn dataify-file
  "Converts an org file into a bunch of data."
  [config]
  (let [file-json (-> config :curr-file :as-json)
        file-edn  (-> file-json (json/parse-string true))]
    (config/update-curr-file config {:as-edn file-edn})))


(defn munge-file
  "After dataify-file,  we extract information and store it in curr-file."
  [config]
  (config/update-curr-file
   config
   {:keywords    (config/get-keywords config)
    :org-title   (config/get-keyword config "TITLE")}))
       


(defn htmlify-file
  "Renders files according to their `layout` keyword."
  [config]
  (let [layout   (config/get-layout config)
        as-html  (when-not (config/file-is-private? config)
                   (layout/apply-template config layout))]

    (config/update-curr-file config {:as-html as-html})))

(defn write-file
  "Takes (file-)config input and writes html to output."
  [{:keys [ curr-file] :as config}]
  (let [curr-file-name (curr-file :name)
        out-file-name  (build-file-outpath config)
        out-html       (curr-file :as-html)]
    (println "Writing file: " curr-file-name "to " out-file-name)
    (when-not (config/file-is-private? config)
      (io/make-parents out-file-name)
      (spit out-file-name out-html))))


(defn -main
  "TODO:  Messy. move the `let` block into the `setup` fn"
  [& args]
  (let [files-dir          (first args)
        config             (config/default files-dir)
        config-with-layout (setup config) ;; side effectful
        org-files          (-> config get-files :org-files)]

    (doseq [f org-files]
      (->> f
           (config/set-curr-file-original config-with-layout)
           (read-file)
           (dataify-file)
           (munge-file)
           (htmlify-file)
           (write-file)))
    #_(if config/dev?
        (System/exit 0))))

;; (-main) ; I recommend not running this in your repl with many files. See test suite instead.
