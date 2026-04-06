(ns dev.freeformsoftware.security-reminder.test-helpers
  (:require
   [babashka.fs :as fs]
   [dev.freeformsoftware.security-reminder.schedule.engine :as engine]
   [integrant.core :as ig]))

(def test-people
  [{:id "p1" :name "Alice" :phone "+1111" :admin? false}
   {:id "p2" :name "Bob" :phone "+2222" :admin? false}
   {:id "p3" :name "Carol" :phone "+3333" :admin? false}
   {:id "p4" :name "Dave" :phone "+4444" :admin? false}
   {:id "p5" :name "Eve" :phone "+5555" :admin? true}])

(def test-twilio-conf
  {:twilio-account-sid "test-sid"
   :twilio-auth-token  "test-token"
   :twilio-from-number "+0000000"
   :twilio-mock?       true})

(defn with-test-engine
  "Creates an engine with a temp dir and test people. Cleans up after.
   The engine env includes :today-str, :twilio-conf, :base-url, and a no-op :on-assignment-change."
  ([f] (with-test-engine test-people f))
  ([people f]
   (let [tmp-dir (str (fs/create-temp-dir {:prefix "sr-test-"}))
         eng     (ig/init-key ::engine/engine
                              {:db-folder             tmp-dir
                               :people                people
                               :twilio-conf           test-twilio-conf
                               :base-url              "http://test.local"
                               :notify-at-days-before [8 1]})
         env     (assoc eng :on-assignment-change (fn []))]
     (try
       (f env)
       (finally
        (ig/halt-key! ::engine/engine eng)
        (fs/delete-tree tmp-dir))))))
