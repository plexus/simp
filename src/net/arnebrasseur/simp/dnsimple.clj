(ns net.arnebrasseur.simp.dnsimple
  "DNSimple provider implementation"
  (:require
   [clojure.string :as str]
   [hato.client :as hato]
   [net.arnebrasseur.simp.protocols :as protocols]))

(def base-url "https://api.dnsimple.com/v2")

(defn- fetch [token path & [opts]]
  (let [req (merge
             {:url (str base-url (if (vector? path)
                                   (str "/" (str/join "/" path))
                                   path))
              :request-method :get
              :content-type :json
              :as :auto
              :headers {"Content-Type" "application/json"
                        "Authorization" (str "Bearer " token)}}
             opts)]
    (hato/request req)))

(defn- fetch-coll [token path & [opts]]
  (let [url                       (if (vector? path)
                                    (str "/" (str/join "/" path))
                                    path)
        {:keys [body]}            (fetch token (str url "?per_page=100") opts)
        {:keys [data pagination]} body]
    (loop [coll  data
           total (:total_pages pagination)
           page  (:current_page pagination)]
      (if (= page total)
        coll
        (let [{:keys [body]}            (fetch token (str url "?per_page=100&page=" (inc page)))
              {:keys [data pagination]} body]
          (recur
           (concat coll data)
           (:total_pages pagination)
           (:current_page pagination)))))))

(defn- normalize-content [content]
  (if (and (str/starts-with? content "\"")
           (str/ends-with? content "\""))
    (str/replace (subs content 1 (dec (count content)))
                 #"\\\"" "\"")
    content))

(defn- get-account-id [token]
  (:id (:account (:data (:body (fetch token "/whoami"))))))

(defn- dnsimple->record [{:keys [zone_id type name content ttl system_record] :as entry}]
  (when-not system_record
    (cond-> {:zone    zone_id
             :name    (or name "")
             :type    type
             :content (normalize-content content)}
      ttl (assoc :ttl ttl)
      (:id entry) (assoc :id (:id entry))
      (:priority entry) (assoc :priority (:priority entry)))))

(defmethod protocols/list-records :dnsimple
  [{:keys [access_token]}]
  (let [account-id (get-account-id access_token)]
    (reduce
     (fn [acc {zone-id :id zone-name :name}]
       (reduce
        (fn [acc entry]
          (if-let [record (dnsimple->record entry)]
            (conj acc record)
            acc))
        acc
        (fetch-coll access_token [account-id "zones" zone-id "records"])))
     []
     (fetch-coll access_token [account-id "zones"]))))

(defmethod protocols/create-record :dnsimple
  [{:keys [access_token]} {:keys [zone name type content ttl priority]}]
  (let [account-id (get-account-id access_token)]
    (fetch access_token [account-id "zones" zone "records"]
           {:request-method :post
            :form-params (cond-> {:name    name
                                  :type    type
                                  :content content}
                           ttl (assoc :ttl ttl)
                           priority (assoc :priority priority))})))

(defmethod protocols/delete-record :dnsimple
  [{:keys [access_token]} {:keys [zone id]}]
  (let [account-id (get-account-id access_token)]
    (fetch access_token [account-id "zones" zone "records" id]
           {:request-method :delete})))

(defmethod protocols/update-record :dnsimple
  [{:keys [access_token]} old-record {:keys [name content ttl priority]}]
  (let [account-id (get-account-id access_token)]
    (fetch access_token [account-id "zones" (:zone old-record) "records" (:id old-record)]
           {:request-method :patch
            :form-params (cond-> {:id      (:id old-record)
                                  :name    name
                                  :content content}
                           ttl (assoc :ttl ttl)
                           priority (assoc :priority priority))})))
