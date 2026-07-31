(ns net.arnebrasseur.simp.dreamhost
  "Dreamhost provider implementation"
  (:require
   [charred.api :as charred]
   [clojure.string :as str]
   [hato.client :as hato]
   [net.arnebrasseur.simp.protocols :as protocols]))

(def base-url "https://api.dreamhost.com")

(defn- api-call [api-key cmd & params]
  (let [query-str (str base-url
                       "/?key=" api-key
                       "&cmd=" cmd
                       "&format=json"
                       (when (seq params)
                         (apply str (for [[k v] (partition 2 params)]
                                      (str "&" (name k) "=" (java.net.URLEncoder/encode (str v) "UTF-8"))))))
        {:keys [body status]} (hato/request {:url query-str
                                             :request-method :get
                                             :as :string})]
    (when (= 200 status)
      (charred/read-json body :key-fn keyword))))

(defn strip-zone
  "Given a full record name and a zone, return the subdomain part.
  e.g. (strip-zone \"www.squid.casa\" \"squid.casa\") => \"www\""
  [record zone]
  (if (= record zone)
    ""
    (let [suffix (str "." zone)]
      (if (str/ends-with? record suffix)
        (subs record 0 (- (count record) (count suffix)))
        record))))

(defn- dreamhost->record [{:keys [record type value zone comment editable]}]
  (cond-> {:zone    zone
           :name    (strip-zone record zone)
           :type    type
           :content value}
    (not (str/blank? comment)) (assoc :comment comment)
    (= "1" editable) (assoc :editable true)
    (= "0" editable) (assoc :editable false)))

(defmethod protocols/list-records :dreamhost
  [{:keys [api_key]}]
  (let [resp (api-call api_key "dns-list_records")]
    (when (= "success" (:result resp))
      (mapv dreamhost->record (:data resp)))))

(defmethod protocols/create-record :dreamhost
  [{:keys [api_key]} {:keys [zone name type content comment]}]
  (let [record-name (if (str/blank? name) zone (str name "." zone))
        resp        (api-call api_key "dns-add_record"
                              :record record-name
                              :type type
                              :value content
                              :comment (or comment ""))]
    (when (= "success" (:result resp))
      {:zone zone :name name :type type :content content})))

(defmethod protocols/delete-record :dreamhost
  [{:keys [api_key]} {:keys [zone name type content]}]
  (let [record-name (if (str/blank? name) zone (str name "." zone))]
    (api-call api_key "dns-remove_record"
              :record record-name
              :type type
              :value content)))

(defmethod protocols/update-record :dreamhost
  [{:keys [api_key] :as cfg} old-record new-record]
  (protocols/delete-record cfg old-record)
  (protocols/create-record cfg new-record))
