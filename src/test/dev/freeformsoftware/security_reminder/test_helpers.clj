(ns dev.freeformsoftware.security-reminder.test-helpers
  (:require
   [babashka.fs :as fs]
   [dev.freeformsoftware.security-reminder.schedule.engine :as engine]
   [integrant.core :as ig]))

(def test-people
  [{:id "p1" :name "Alice" :email "alice@test.local" :admin? false}
   {:id "p2" :name "Bob" :email "bob@test.local" :admin? false}
   {:id "p3" :name "Carol" :email "carol@test.local" :admin? false}
   {:id "p4" :name "Dave" :email "dave@test.local" :admin? false}
   {:id "p5" :name "Eve" :email "eve@test.local" :admin? true}])

(defn with-test-engine
  "Creates an engine with a temp dir and test people. Cleans up after.
   The engine env includes :today-str, :base-url, and a no-op :on-assignment-change."
  ([f] (with-test-engine test-people f))
  ([people f]
   (let [tmp-dir (str (fs/create-temp-dir {:prefix "sr-test-"}))
         eng     (ig/init-key ::engine/engine
                              {:db-folder             tmp-dir
                               :people                people
                               :base-url              "http://test.local"
                               :notify-at-days-before [8 1]})
         env     (assoc eng :on-assignment-change (fn []))]
     (try
       (f env)
       (finally
         (ig/halt-key! ::engine/engine eng)
         (fs/delete-tree tmp-dir))))))
