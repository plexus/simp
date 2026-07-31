(ns net.arnebrasseur.simp.protocols
  "Multimethods for DNS provider operations. Each provider implements these
  to normalize provider-specific API details into a common record format.

  Common record format:
    :zone    - top-level domain name (String)
    :name    - subdomain part, \"\" for apex (String)
    :type    - record type: A, CNAME, MX, TXT, SRV, AAAA, etc. (String)
    :content - the record value (String)
    :ttl     - time to live in seconds, may be nil (Long or nil)
    :priority - MX priority, may be nil (Long or nil)
    :id      - provider-specific record ID, may be nil (String or nil)"

  (:require
   [clojure.string :as str]))

(defmulti list-records
  "Fetch all DNS records from the provider.
  Returns a seq of normalized record maps."
  (fn [provider-cfg & _] (:provider provider-cfg)))

(defmulti create-record
  "Create a new DNS record.
  `record` is a normalized record map with at least :zone, :name, :type, :content.
  Returns the created record (normalized)."
  (fn [provider-cfg _] (:provider provider-cfg)))

(defmulti delete-record
  "Delete an existing DNS record.
  `record` is a normalized record map."
  (fn [provider-cfg _] (:provider provider-cfg)))

(defmulti update-record
  "Update an existing DNS record.
  `old-record` is the current (normalized) record from the provider.
  `new-record` is the desired (normalized) record."
  (fn [provider-cfg _ _] (:provider provider-cfg)))
