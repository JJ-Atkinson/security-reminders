(ns dev.freeformsoftware.security-reminder.patches.huff-malli
  "Monkey-patch for huff 0.1.x / malli 0.19.x incompatibility.
   Malli 0.19+ changed :orn parser output from plain vectors to malli.core.Tag
   records. Huff's emit multimethod dispatches on (first form), which now yields
   a MapEntry [:key :tag-node] instead of the keyword :tag-node.
   This patch rewrites huff.core/html to unwrap Tags before emitting.

   Remove this once garden-email upgrades to huff 0.2.x (which uses huff2.core)."
  (:require
   [huff.core :as huff])
  (:import
    [malli.core Tag Tags]))

(set! *warn-on-reflection* true)

(defn- unwrap-malli-tag
  "Recursively convert malli 0.19+ Tag/Tags records back to the plain
   vectors/maps that huff 0.1.x emit expects."
  [x]
  (cond
    (instance? Tag x)
    (let [k (.key ^Tag x)
          v (.value ^Tag x)]
      [k (unwrap-malli-tag v)])

    (instance? Tags x)
    (let [vals (.values ^Tags x)]
      (cond
        (map? vals)
        (into {} (map (fn [[k v]] [k (unwrap-malli-tag v)])) vals)

        ;; malli 0.19 :catn returns Tags with a ValSeq wrapping the parsed map
        (seq? vals)
        (unwrap-malli-tag (first vals))

        :else
        (unwrap-malli-tag vals)))

    (map? x)
    (into {} (map (fn [[k v]] [k (unwrap-malli-tag v)])) x)

    (vector? x)
    (mapv unwrap-malli-tag x)

    :else x))

(alter-var-root
 #'huff/html
 (constantly
  (fn html
    ([h] (html {} h))
    ([{:keys [allow-raw] :or {allow-raw false}} h]
     (let [parsed (@#'huff/parser h)]
       (if (= parsed :malli.core/invalid)
         (let [{:keys [value]} (@#'huff/explainer h)]
           (throw (ex-info "Invalid huff form" {:value value})))
         (let [sb (StringBuilder.)]
           (@#'huff/emit sb (unwrap-malli-tag parsed) {:allow-raw allow-raw})
           (str sb))))))))
