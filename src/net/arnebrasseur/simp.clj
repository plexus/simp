(ns net.arnebrasseur.simp
  "Gitops for DNS"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [hato.client :as hato]
   [lambdaisland.cli :as cli]
   [toml-clj.core :as toml])
  (:import
   (java.nio.file Files Paths)
   (java.nio.file.attribute PosixFilePermissions)))

(require 'net.arnebrasseur.hato-charred)

(defn config-file-path []
  (io/file
   (or (System/getenv "XDG_CONFIG_HOME")
       (io/file (System/getProperty "user.home") ".config/simp/config.toml"))))

(defn read-config [cfg-path]
  (with-open [rdr (io/reader cfg-path)]
    (toml/read rdr {:key-fn keyword})))

(defn ensure-config [cfg-path]
  (when-not (.exists cfg-path)
    (.mkdirs (io/file (.getParent cfg-path)))
    (spit cfg-path "[dnsimple]\naccess_token=\"\""))
  (Files/setPosixFilePermissions
   (Paths/get (str cfg-path) (into-array String []))
   (PosixFilePermissions/fromString "rw-------")))

(def init
  {:verbosity 0
   :base-url "https://api.dnsimple.com/v2"
   :dnsimple/token
   (let [cfg-path (doto (config-file-path) ensure-config)
         cfg      (read-config cfg-path)
         token    (str (-> cfg :dnsimple :access_token))]
     (when-not (str/starts-with? token "dnsimple_")
       (println "Set up your dnsimple access token in" cfg-path)
       (System/exit -1))
     token)})

(defn debug [& args]
  (when (< 0 (:verbosity cli/*opts*))
    (apply println args)))

(defn trace [& args]
  (when (< 1 (:verbosity cli/*opts*))
    (apply println args)))

(defn fetch [path & [opts]]
  (let [req (merge
             {:url (str (:base-url init) (if (vector? path)
                                           (str "/" (str/join "/" path))
                                           path))
              :request-method :get
              :content-type :json
              :as :auto
              :headers {"Content-Type" "application/json"
                        "Authorization" (str "Bearer " (:dnsimple/token init))}}
             opts)]
    (debug (str/upper-case (name (:request-method req))) (:url req))
    (when-let [params (:form-params req)]
      (trace params))
    (hato/request req)))

(defn fetch-coll [path & [opts]]
  (let [url                       (if (vector? path)
                                    (str "/" (str/join "/" path))
                                    path)
        {:keys [body]}            (fetch (str url "?per_page=100") opts)
        {:keys [data pagination]} body]
    (loop [coll  data
           total (:total_pages pagination)
           page  (:current_page pagination)]
      (if (= page total)
        coll
        (let [{:keys [body]}            (fetch (str url "?per_page=100&page=" (inc page)))
              {:keys [data pagination]} body]
          (recur
           (concat coll data)
           (:total_pages pagination)
           (:current_page pagination)))))))

(defn normalize-content [content]
  (if (and (str/starts-with? content "\"")
           (str/ends-with? content "\""))
    (str/replace (subs content 1 (dec (count content)))
                 #"\\\"" "\"")
    content))

(defn fetch-all-records [account-id]
  (reduce
   (fn [acc {zone-id :id zone-name :name}]
     (reduce
      (fn [acc {:keys [zone_id type name content ttl system_record] :as entry}]
        (if system_record
          acc
          (conj acc (update entry :content normalize-content))))
      acc
      (fetch-coll [account-id "zones" zone-id "records"])))
   []
   (fetch-coll [account-id "zones"])))

(defn render-line [{:keys [type content ttl priority] :as record}]
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

(defn domain-file-contents [entries]
  (str
   (str/join
    "\n\n"
    (for [[name records] (into (sorted-map) (group-by :name entries))]
      (str (section-str (:zone_id (first records)) name) "\n"
           (str/join
            "\n"
            (for [record (sort-by (juxt :type :content) records)]
              (render-line record))))))
   "\n\n\n# Local Variables:\n# mode:conf\n# End:\n"))

(defn recreate-domain-files! [dir records]
  (.mkdirs (io/file dir))
  (doseq [[zone entries] (group-by :zone_id records)]
    (debug "Writing" (str (io/file dir zone)))
    (spit (io/file dir zone)
          (domain-file-contents entries))))

(defn parse-line [line]
  (let [[_ type rest] (re-find #"([A-Z]+)=(.*)" line)
        rdr           (java.io.PushbackReader. (java.io.StringReader. rest))
        content       (read rdr) ; read value as a clojure string
        rest          (first (.toList (.lines (java.io.BufferedReader. rdr)))) ; remaining portion of the line
        kvs           (when rest
                        (into {}
                              (map #(str/split % #"="))
                              (-> rest
                                  (str/replace #"#.*" "")
                                  str/trim
                                  (str/split #"\s+"))))]
    (into
     {:type    type
      :content content}
     (-> kvs
         (update-keys keyword)
         (update-vals #(if (re-find #"^\d+$" %) (parse-long %) %))))))

(defn parse-domain-file [zone]
  (let [lines (str/split (slurp (io/file "domains" zone)) #"\R")]
    (loop [acc []
           section nil
           [line & lines] lines]
      (if-not line
        acc
        (cond
          (re-find #"^(\s*|\s*#.*)$" line)
          (recur acc section lines)

          (re-find #"^\s*\[(.*)\]\s*($|#.*$)" line)
          (let [[_ section] (re-find #"^\s*\[(.*)\]\s*($|#.*$)" line)]
            (if (= zone section)
              (recur acc "" lines)
              (recur acc (subs section 0 (- (count section) (count zone) 1)) lines)))

          :else
          (recur
           (conj acc (assoc (parse-line line)
                            :name section
                            :zone_id zone))
           section
           lines))))))

(defn minimal-record [record]
  (cond-> (select-keys record [:name :content :type :ttl :priority :zone_id])
    (= 3600 (:ttl record))
    (dissoc :ttl)
    (nil? (:priority record))
    (dissoc :priority)))

(defn parse-domain-files [directory]
  (mapcat #(parse-domain-file (.getName %)) (next (file-seq (io/file directory)))))

(defn get-account-id []
  (:id (:account (:data (:body (fetch "/whoami"))))))

(def rec-comp "Record comparator function" (juxt :zone_id :name :type))

(defn diff [dr fr]
  (let [drg (update-vals (group-by rec-comp dr) #(sort-by (juxt rec-comp :content) %))
        frg (update-vals (group-by rec-comp fr) #(sort-by (juxt rec-comp :content) %))]
    (reduce
     (fn [acc k]
       (let [orig'(get drg k)
             new' (get frg k)
             orig (vec (remove (fn [o] (some #(= (minimal-record o) %) new')) orig'))
             new  (vec (remove (fn [n] (some #(= (minimal-record %) n) orig')) new'))]
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
                  (update-in acc [(:zone_id n) :added] (fnil conj []) n)
                  (nil? n)
                  (update-in acc [(:zone_id o) :removed] (fnil conj []) o)
                  :else
                  (update-in acc [(:zone_id o) :changed] (fnil conj []) [o n]))))
            acc
            (range (max (count orig) (count new)))))))
     {}
     (distinct (mapcat keys [drg frg])))))


(defn init-dir
  "(Re-)create domain files based on DNSimple records"
  [opts]
  (println "WARN: this will overwrite your domains files, continue? [y/n]")
  (when (= "y" (str/trim (read-line)))
    (let [account-id       (get-account-id)
          dnsimple-records (map minimal-record (fetch-all-records account-id))]
      (recreate-domain-files! "domains" dnsimple-records))))

(defn red [& ss] (str "\u001B[31m" (apply str ss) "\u001B[0m"))
(defn green [& ss] (str "\u001B[32m" (apply str ss) "\u001B[0m"))
(defn yellow [& ss] (str "\u001B[33m" (apply str ss) "\u001B[0m"))

(defn print-diff [diff]
  (doseq [[zone {:keys [added removed changed]}] diff]
    (println (section-str zone ""))
    (doseq [r removed]
      (println (red "-" " " (section-str zone (:name r)) " " (render-line r))))
    (doseq [[o n] changed]
      (println (yellow "~" " " (section-str zone (:name o)) " " (render-line o) "->" (render-line n))))
    (doseq [r added]
      (println (green "+" " " (section-str zone (:name r)) " " (render-line r))))))

(defn show-changes
  "Show a diff of the changes that `apply` would apply"
  [opts]
  (let [account-id                      (get-account-id)
        dnsimple-records                (fetch-all-records account-id)
        file-records                    (parse-domain-files "domains")]
    (print-diff (diff dnsimple-records file-records))
    ))

(defn apply-changes
  "Apply changes from domain files to DNSimple"
  [opts]
  (let [account-id       (get-account-id)
        dnsimple-records (fetch-all-records account-id)
        file-records     (parse-domain-files "domains")
        diff             (diff dnsimple-records file-records)]
    (println "Changeset:")
    (print-diff diff)
    (println "Continue? [y/n]")
    (when (= "y" (str/trim (read-line)))
      (doseq [[zone {:keys [added removed changed]}] diff]
        (doseq [r removed]
          (fetch [account-id "zones" zone "records" (:id r)]
                 {:request-method :delete}))
        (doseq [[o n] changed]
          (fetch [account-id "zones" zone "records" (:id o)]
                 {:request-method :patch
                  :form-params (assoc (select-keys n [:name :content :ttl :priority]) :id (:id o))}))
        (doseq [r added]
          (fetch [account-id "zones" zone "records"]
                 {:request-method :post
                  :form-params (select-keys r [:name :type :content :ttl :priority])}))))))

(def flags
  ["-v,--verbose" {:doc "Increase verbosity"
                   :key :verbosity}])

(def commands
  ["init" #'init-dir
   "apply" #'apply-changes
   "diff" #'show-changes])

(defn -main [& args]
  (cli/dispatch*
   {:name     "simp"
    :doc      "Gitops for DNSimple"
    :init     init
    :commands commands
    :flags    flags}
   args))

(comment
  (let [account-id (get-account-id)
        dnsimple-records (map minimal-record (fetch-all-records account-id))
        file-records (parse-domain-files "domains")]
    #_(recreate-domain-files! dnsimple-records)
    #_(doseq [r (remove (set file-records) (set dnsimple-records))]
        (println "-" (render-line r)))
    (doseq [r (remove (set dnsimple-records) (set file-records))]
      (println "+" (section-str (:zone_id r) (:name r)) (render-line r))
      #_(fetch [account-id "zones" (:zone_id r) "records"]
               {:request-method :post
                :form-params (select-keys r [:name :content :type :ttl :priority])}))))
