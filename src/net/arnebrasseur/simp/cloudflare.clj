(ns net.arnebrasseur.simp.cloudflare
  "Cloudflare DNS provider implementation"
  (:require
   [clojure.string :as str]
   [hato.client :as hato]
   [net.arnebrasseur.simp.protocols :as protocols]))

(def base-url "https://api.cloudflare.com/client/v4")

(defn- request
  [api_token path & [opts]]
  (let [req (merge
             {:url (str base-url (if (vector? path)
                                   (str "/" (str/join "/" path))
                                   path))
              :request-method :get
              :content-type :json
              :as :auto
              :headers {"Authorization" (str "Bearer " api_token)}}
             opts)]
    (hato/request req)))

(defn- fetch-coll
  [api_token path]
  (let [url                             (if (vector? path)
                                          (str "/" (str/join "/" path))
                                          path)
        {:keys [body]}                  (request api_token (str url "?per_page=100"))
        {:keys [result result_info]}    body]
    (loop [coll  result
           total (:total_pages result_info)
           page  (:page result_info)]
      (if (<= total page)
        coll
        (let [{:keys [body]} (request api_token (str url "?per_page=100&page=" (inc page)))
              {:keys [result result_info]} body]
          (recur
           (concat coll result)
           (:total_pages result_info)
           (:page result_info)))))))

(defn- normalize-content
  [content]
  (if (and (str/starts-with? content "\"")
           (str/ends-with? content "\""))
    (str/replace (subs content 1 (dec (count content)))
                 #"\\\"" "\"")
    content))

(defn- strip-zone
  [name zone-name]
  (if (= name zone-name)
    ""
    (let [suffix (str "." zone-name)]
      (if (str/ends-with? name suffix)
        (subs name 0 (- (count name) (count suffix)))
        name))))

(defn- cloudflare->record
  [zone-name {:keys [type name content ttl priority id] :as entry}]
  (cond-> {:zone    zone-name
           :name    (strip-zone name zone-name)
           :type    type
           :content (normalize-content content)}
    ttl (assoc :ttl ttl)
    id (assoc :id id)
    priority (assoc :priority priority)))

(defn- get-zone-id
  [api_token zone-name]
  (:id (first
        (filter #(= zone-name (:name %))
                (fetch-coll api_token "/zones")))))

(defmethod protocols/list-records :cloudflare
  [{:keys [api_token]} zones]
  (reduce
   (fn [acc {zone-id :id zone-name :name}]
     (if (and (seq zones) (not (some #{zone-name} zones)))
       acc
       (reduce
        (fn [acc entry]
          (conj acc (cloudflare->record zone-name entry)))
        acc
        (fetch-coll api_token ["zones" zone-id "dns_records"]))))
   []
   (fetch-coll api_token "/zones")))

(defmethod protocols/create-record :cloudflare
  [{:keys [api_token]} {:keys [zone name type content ttl priority]}]
  (let [zone-id    (get-zone-id api_token zone)
        record-name (if (str/blank? name) zone (str name "." zone))
        body        (:body (request api_token ["zones" zone-id "dns_records"]
                                    {:request-method :post
                                     :form-params (cond-> {:type    type
                                                           :name    record-name
                                                           :content content}
                                                    ttl (assoc :ttl ttl)
                                                    priority (assoc :priority priority))}))]
    (cloudflare->record zone (:result body))))

(defmethod protocols/delete-record :cloudflare
  [{:keys [api_token]} {:keys [zone id]}]
  (let [zone-id (get-zone-id api_token zone)]
    (request api_token ["zones" zone-id "dns_records" id]
             {:request-method :delete})))

(defmethod protocols/update-record :cloudflare
  [{:keys [api_token]} old-record {:keys [name content ttl priority]}]
  (let [zone-id     (get-zone-id api_token (:zone old-record))
        record-name (if (str/blank? name) (:zone old-record) (str name "." (:zone old-record)))
        body         (:body (request api_token ["zones" zone-id "dns_records" (:id old-record)]
                                     {:request-method :patch
                                      :form-params (cond-> {:type    (:type old-record)
                                                            :name    record-name
                                                            :content content}
                                                     ttl (assoc :ttl ttl)
                                                     priority (assoc :priority priority))}))]
    (cloudflare->record (:zone old-record) (:result body))))

(comment
  ;; Example usage in REPL
  (protocols/list-records {:provider   :cloudflare
                           :api_token  "YOUR_API_TOKEN"})

  (protocols/create-record {:provider  :cloudflare
                            :api_token "YOUR_API_TOKEN"}
                           {:zone "example.com"
                            :name "www"
                            :type "A"
                            :content "1.2.3.4"
                            :ttl 3600})

  (protocols/delete-record {:provider  :cloudflare
                            :api_token "YOUR_API_TOKEN"}
                           {:zone "example.com"
                            :id "record-id-here"})

  (protocols/update-record {:provider  :cloudflare
                            :api_token "YOUR_API_TOKEN"}
                           {:zone "example.com"
                            :id "record-id-here"
                            :type "A"}
                           {:name "www"
                            :content "5.6.7.8"
                            :ttl 600}))
