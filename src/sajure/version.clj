(ns sajure.version
  "Version information for the sajure port.

  Spec v2: the port string is the literal \"v2\" (NOT the reference impl's
  semantic 1.1.0). See README.org and spec.org header (#+VERSION).")

(def ^:const version
  "Port version string — exactly \"v2\" per the v2 spec milestone."
  "v2")

(defn version-string
  "Returns the version string \"v2\"."
  []
  version)

(defn version-info
  "Structured version info for serverInfo / diagnostics."
  []
  {"name"    "sajure"
   "version" version
   "spec"    "v2"})
