(ns net.arnebrasseur.simp
  "Gitops for DNS"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [lambdaisland.cli :as cli]
   [toml-clj.core :as toml]
   [net.arnebrasseur.simp.protocols :as protocols]
   [net.arnebrasseur.simp.cloudflare]
   [net.arnebrasseur.simp.dnsimple]
   [net.arnebrasseur.simp.dreamhost])
  (:import
   (java.nio.file Files Paths)
   (java.nio.file.attribute PosixFilePermissions)))

(require 'net.arnebrasseur.simp.hato-charred)

;;; Config

(defn config-file-path []
  (io/file
   (or (System/getenv "XDG_CONFIG_HOME")
       (io/file (System/getProperty "user.home") ".config/simp/config.toml"))))

(defn ensure-config [cfg-path]
  (when-not (.exists cfg-path)
    (.mkdirs (io/file (.getParent cfg-path)))
    (spit cfg-path "[dnsimple]\naccess_token=\"\""))
  (Files/setPosixFilePermissions
   (Paths/get (str cfg-path) (into-array String []))
   (PosixFilePermissions/fromString "rw-------")))

(defn get-account-cfg [config account-name]
  (let [{:keys [accounts default]} config
        name (or (keyword account-name) account-name (keyword default))]
    (or (get accounts (keyword name))
        (get accounts name))))

;;; CLI init

(defn read-config []
  (let [cfg-path        (doto (config-file-path) ensure-config)
        raw-cfg         (with-open [rdr (io/reader cfg-path)]
                          (toml/read rdr {:key-fn keyword}))
        accounts        (into {}
                              (map (fn [[account-name account-cfg]]
                                     (let [provider (keyword (or (:provider account-cfg)
                                                                 (name account-name)))]
                                       [account-name
                                        (assoc account-cfg
                                               :provider provider
                                               :account account-name)])))
                              raw-cfg)
        default-account (some (fn [[name cfg]] (when (:default cfg) name)) accounts)]
    (assert default-account)
    {:accounts accounts
     :default  default-account}))

;;; HTTP helpers (for general use, not provider-specific)

(defn debug [& args]
  (when (< 0 (:verbosity cli/*opts*))
    (apply println args)))

(defn trace [& args]
  (when (< 1 (:verbosity cli/*opts*))
    (apply println args)))

;;; Record rendering / domain file serialization

(defn render-line [{:keys [type content ttl priority]}]
  (str type "=" (pr-str content)
       (when (and (not (nil? ttl))
                  (not= 3600 ttl))
         (str " ttl=" ttl))
       (when priority
         (str " priority=" priority))))

(defn section-str [zone name]
  (str "["
       (if (str/blank? name)
         zone
         (str name "." zone))
       "]"))

(defn domain-file-contents [account-name entries]
  (str "[simp]\n"
       "account=" (pr-str (name account-name)) "\n"
       "\n"
       (str/join
        "\n\n"
        (for [[name records] (into (sorted-map) (group-by :name entries))]
          (str (section-str (:zone (first records)) name) "\n"
               (str/join
                "\n"
                (for [record (sort-by (juxt :type :content) records)]
                  (render-line record))))))
       "\n\n\n# Local Variables:\n# mode:conf\n# End:\n"))

;;; Domain file parsing

(defn parse-line [line]
  (let [line          (str/trim (str/replace line #"#.*" ""))
        [_ type rest] (re-find #"^([A-Z]+)=(.*)" line)
        rdr           (java.io.PushbackReader. (java.io.StringReader. rest))
        content       (read rdr)
        rest          (first (.toList (.lines (java.io.BufferedReader. rdr))))
        kvs           (when rest
                        (into {}
                              (map #(str/split % #"="))
                              (-> rest
                                  str/trim
                                  (str/split #"\s+"))))]
    (into
     {:type    type
      :content content}
     (-> kvs
         (update-keys keyword)
         (update-vals #(if (re-find #"^\d+$" %) (parse-long %) %))))))

(defn parse-domain-file [file-name]
  (let [lines (str/split (slurp (io/file "domains" file-name)) #"\R")]
    (loop [simp-meta      {}
           records        []
           section        nil
           [line & lines] lines]
      (let [zone (:zone simp-meta file-name)]
        (if-not line
          {:account (:account simp-meta)
           :zone    zone
           :records records}
          (cond
            (re-find #"^(\s*|\s*#.*)$" line)
            (recur simp-meta records section lines)

            (re-find #"^\s*\[(.*)\]\s*($|#.*$)" line)
            (let [[_ sec-name] (re-find #"^\s*\[(.*)\]\s*($|#.*$)" line)]
              (cond
                (= "simp" sec-name)
                (recur simp-meta records "simp" lines)

                (= zone sec-name)
                (recur simp-meta records "" lines)

                :else
                (recur simp-meta records
                       (subs sec-name 0 (- (count sec-name) (count zone) 1))
                       lines)))

            (= section "simp")
            (let [[_ k v] (re-find #"^\s*([a-z_]+)\s*=\s*(.*)" line)]
              (if k
                (recur (assoc simp-meta (keyword k) (try (read-string v) (catch Exception _ v)))
                       records section lines)
                (recur simp-meta records section lines)))

            :else
            (recur simp-meta
                   (conj records (assoc (parse-line line) :name section :zone zone))
                   section
                   lines)))))))

(defn parse-domain-files [directory]
  (mapv #(parse-domain-file (.getName %)) (next (file-seq (io/file directory)))))

;;; Diff

(defn minimal-record [record]
  (cond-> (select-keys record [:name :content :type :ttl :priority :zone])
    (= 3600 (:ttl record))
    (dissoc :ttl)
    (nil? (:priority record))
    (dissoc :priority)))

(def rec-comp "Record comparator function" (juxt :zone :name :type))

(defn diff [dr fr]
  (let [drg (update-vals (group-by rec-comp dr) #(sort-by (juxt rec-comp :content) %))
        frg (update-vals (group-by rec-comp fr) #(sort-by (juxt rec-comp :content) %))]
    (reduce
     (fn [acc k]
       (let [orig' (get drg k)
             new'  (get frg k)
             orig  (vec (remove (fn [o] (some #(= (minimal-record o) %) new')) orig'))
             new   (vec (remove (fn [n] (some #(= (minimal-record %) n) orig')) new'))]
         (if (= orig new)
           acc
           (reduce
            (fn [acc idx]
              (let [o (get orig idx)
                    n (get new idx)]
                (cond
                  (= (minimal-record o) n)
                  acc
                  (nil? o)
                  (update-in acc [(:zone n) :added] (fnil conj []) n)
                  (nil? n)
                  (update-in acc [(:zone o) :removed] (fnil conj []) o)
                  :else
                  (update-in acc [(:zone o) :changed] (fnil conj []) [o n]))))
            acc
            (range (max (count orig) (count new)))))))
     {}
     (distinct (mapcat keys [drg frg])))))

;;; Output formatting

(defn red [& ss] (str "\u001B[31m" (apply str ss) "\u001B[0m"))
(defn green [& ss] (str "\u001B[32m" (apply str ss) "\u001B[0m"))
(defn yellow [& ss] (str "\u001B[33m" (apply str ss) "\u001B[0m"))

(defn print-diff [diff]
  (doseq [[zone {:keys [added removed changed]}] diff]
    (println (section-str zone ""))
    (doseq [r removed]
      (println (red "-" " " (section-str zone (:name r)) " " (render-line r))))
    (doseq [[o n] changed]
      (println (yellow "~" " " (section-str zone (:name o)) " " (render-line o) " -> " (render-line n))))
    (doseq [r added]
      (println (green "+" " " (section-str zone (:name r)) " " (render-line r))))))

;;; Commands

(defn recreate-domain-files! [dir file-records-by-account]
  (.mkdirs (io/file dir))
  (doseq [[account-name file-records] file-records-by-account
          :let [by-zone (group-by :zone file-records)]]
    (doseq [[zone entries] by-zone]
      (debug "Writing" (str (io/file dir zone)))
      (spit (io/file dir zone)
            (domain-file-contents account-name entries)))))

(defn get-file-records-by-account [directory config zones]
  (let [parsed-files (parse-domain-files directory)]
    (reduce (fn [acc {:keys [account zone records]}]
              (if (or (empty? zones) (some #{zone} zones))
                (let [account-name (or account (:default config))]
                  (update acc account-name concat records))
                acc))
            {}
            parsed-files)))

(defn init-dir
  "Pull DNS records from providers, recreate domain files"
  [{:keys [config zones] :as opts}]
  (println "WARN: this will overwrite your domains files, continue? [y/n]")
  (when (= "y" (str/trim (read-line)))
    (doseq [[account-name acct-cfg] (:accounts config)]
      (let [records (protocols/list-records acct-cfg zones)]
        (recreate-domain-files! "domains" {account-name (if (seq zones)
                                                          (filter (comp (set zones) :zone) records)
                                                          records)})))))

(defn show-changes
  "Show a diff of the changes that apply would apply"
  [{:keys [config zones] :as opts}]
  (let [file-records-by-acct (get-file-records-by-account "domains" config zones)]
    (doseq [[account-name file-records] file-records-by-acct
            :when (or (empty? (:account opts)) (some #{account-name} (:account opts)))
            :let [acct-cfg (get-account-cfg config account-name)
                  remote   (protocols/list-records acct-cfg zones)
                  ;; only compare zones present in file records
                  zones    (set (map :zone file-records))
                  remote   (filter #(contains? zones (:zone %)) remote)
                  d        (diff remote file-records)]
            :when (seq d)]
      (println "Account:" account-name (str "(" (:provider acct-cfg) ")"))
      (print-diff d))))

(defn apply-changes
  "Apply changes from domain files to DNS providers"
  [{:keys [config zones] :as opts}]
  (let [file-records-by-acct (get-file-records-by-account "domains" config zones)]
    (println "Changeset:")
    (doseq [[account-name file-records] file-records-by-acct
            :when (or (empty? (:account opts)) (some #{account-name} (:account opts)))
            :let [acct-cfg (get-account-cfg config account-name)
                  remote   (protocols/list-records acct-cfg zones)
                  zones    (set (map :zone file-records))
                  remote   (filter #(contains? zones (:zone %)) remote)
                  d        (diff remote file-records)]
            :when (seq d)]
      (println "Account:" account-name (str "(" (:provider acct-cfg) ")"))
      (print-diff d))
    (println "Continue? [y/n]")
    (when (= "y" (str/trim (read-line)))
      (doseq [[account-name file-records] file-records-by-acct]
        (let [acct-cfg (get-account-cfg config account-name)
              remote   (protocols/list-records acct-cfg zones)
              zones    (set (map :zone file-records))
              remote   (filter #(contains? zones (:zone %)) remote)
              d        (diff remote file-records)]
          (doseq [[zone {:keys [added removed changed]}] d]
            (doseq [r removed]
              (protocols/delete-record acct-cfg r))
            (doseq [[o n] changed]
              (protocols/update-record acct-cfg o n))
            (doseq [r added]
              (protocols/create-record acct-cfg r))))))))

;;; CLI

(def flags
  ["-v,--verbose" {:doc "Increase verbosity"
                   :key :verbosity}
   "--account <account>" {:doc "Select specific account"
                          :coll? true}
   "-z,--zone <zone>"  {:doc "Select specific zone"
                        :key :zones
                        :coll? true}])

(def commands
  ["init" #'init-dir
   "apply" #'apply-changes
   "diff" #'show-changes])

(defn wrap-filter-accounts [cmd]
  (fn [opts]
    (cmd
     (if-let [accounts (:account opts)]
       (update-in opts [:config :accounts] select-keys (map keyword accounts))
       opts))))

(defn -main [& args]
  (cli/dispatch*
   {:name       "simp"
    :doc        "Gitops for DNS"
    :init       {:verbosity 0
                 :config (read-config)}
    :commands   commands
    :flags      flags
    :middleware [#'wrap-filter-accounts]}
   args))
