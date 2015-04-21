(ns onyx.messaging.aeron-media-driver
  (:require [clojure.core.async :refer [chan <!!]])
  (:import [uk.co.real_logic.aeron Aeron FragmentAssemblyAdapter]
           [uk.co.real_logic.aeron Aeron$Context]
           [uk.co.real_logic.aeron.driver MediaDriver MediaDriver$Context ThreadingMode]))

(defn -main [& args]
  (let [ctx (doto (MediaDriver$Context.) 
              (.threadingMode ThreadingMode/SHARED)
              ;(.threadingMode ThreadingMode/DEDICATED)
              (.dirsDeleteOnExit true))
        media-driver (MediaDriver/launch ctx)]
    media-driver)
  (<!! (chan)))

