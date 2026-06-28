(ns sajure.tools
  "§3 Tool system: path containment (safe-path?), the permission model
  (safe/unsafe + YOLO), and an IMMUTABLE tool registry.

  Registry modeling (see README 'spec ambiguities'): unlike guile-sage's
  mutable set!-box, the registry here is a plain immutable Clojure value — a
  vector of tool maps. Each tool is {:name :description :schema :safe :exec};
  :exec is a function held as a value. Lookups are pure; there is no global
  mutable state. Callers thread the registry explicitly (the mcp-server does)."
  (:require [clojure.string :as str]
            [sajure.config :as config]))

;;; ---------------------------------------------------------------------------
;;; §3 Path containment — safe-path? MUST be TOTAL (never throws).
;;; ---------------------------------------------------------------------------

(def nul-char "A single NUL byte, as a String." (str (char 0)))

(defn blocked-segment?
  "PER-TOKEN sensitive-name rule (authoritative spec §3 — neither a plain
  substring nor a plain exact match; both are wrong):
    - `.env`  family: a segment EQUAL to `.env` OR STARTING WITH `.env.`
              (blocks `.env`, `.env.local`, `.env.production`; allows `my.env`
              and `.gitignore`).
    - `.git` / `.ssh` / `.gnupg`: EXACT segment only (allows `.gitignore`,
              `.gitattributes`).
  This block applies to ALL paths — including under `/tmp/` (so
  `/tmp/.ssh/id_rsa` is rejected)."
  [seg]
  (boolean
   (or (= seg ".env")
       (str/starts-with? seg ".env.")
       (= seg ".git")
       (= seg ".ssh")
       (= seg ".gnupg"))))

(defn workspace
  "Workspace root: SAGE_WORKSPACE or the process cwd."
  []
  (or (config/get-config "WORKSPACE")
      (System/getProperty "user.dir")))

(defn safe-path?
  "TOTAL predicate (never raises on hostile input). True iff PATH is allowed:
    - non-empty string (empty rejected);
    - contains no NUL byte;
    - contains no `..` substring (traversal);
    - no path SEGMENT matches the PER-TOKEN sensitive rule (blocked-segment?),
      applied to ALL paths including /tmp/;
    - resolves under the workspace (relative paths resolve against it) or /tmp/."
  [path]
  (try
    (boolean
     (and (string? path)
          (pos? (count path))
          (not (str/includes? path nul-char))
          (not (str/includes? path ".."))
          (let [ws (workspace)
                resolved (if (str/starts-with? path "/")
                           path
                           (str ws "/" path))
                segs (str/split resolved #"/")]
            (and (not-any? blocked-segment? segs)
                 (or (str/starts-with? resolved "/tmp/")
                     (= resolved ws)
                     (str/starts-with? resolved (str ws "/")))))))
    (catch Throwable _ false)))

;;; ---------------------------------------------------------------------------
;;; §3 Permission model
;;; ---------------------------------------------------------------------------

(defn yolo? [] (config/flag? "YOLO_MODE"))

(defn allowed?
  "Safe tools are always allowed. Unsafe tools require YOLO mode (or a caller
  confirmation, modeled by CONFIRM?)."
  ([tool] (allowed? tool false))
  ([tool confirm?]
   (boolean (or (:safe tool) (yolo?) confirm?))))

;;; ---------------------------------------------------------------------------
;;; Immutable registry
;;; ---------------------------------------------------------------------------

(defn make-tool
  "Construct a tool map. SAFE defaults false (unsafe)."
  [name description schema exec & {:keys [safe] :or {safe false}}]
  {:name name :description description :schema schema :exec exec :safe safe})

(defn find-tool
  "Look up a tool by name in REGISTRY (a vector of tool maps). Pure."
  [registry name]
  (first (filter #(= (:name %) name) registry)))

(defn safe-tool? [registry name]
  (boolean (:safe (find-tool registry name))))

(defn to-schema
  "Project REGISTRY to MCP tools/list shape (string-keyed maps)."
  [registry]
  (mapv (fn [t]
          {"name" (:name t)
           "description" (:description t)
           "inputSchema" (:schema t)})
        registry))

;;; A small default registry exercising both safe and unsafe tools. This is a
;;; representative subset of the §3 ~34-tool registry — enough to drive the MCP
;;; server's exposure / no-oracle boundary.
(def default-registry
  [(make-tool "echo" "Echo the given text back."
              {"type" "object"
               "properties" {"text" {"type" "string"}}
               "required" ["text"]}
              (fn [args] (str (get args "text" "")))
              :safe true)
   (make-tool "whoami" "Report the agent identity."
              {"type" "object" "properties" {}}
              (fn [_] "sajure v2")
              :safe true)
   (make-tool "read_file" "Read a workspace file (path-contained)."
              {"type" "object"
               "properties" {"path" {"type" "string"}}
               "required" ["path"]}
              (fn [args]
                (let [p (get args "path")]
                  (if (safe-path? p)
                    (str "(would read " p ")")
                    "[error] path rejected by safe-path?")))
              :safe true)
   (make-tool "write_file" "Write a workspace file (UNSAFE)."
              {"type" "object"
               "properties" {"path" {"type" "string"} "content" {"type" "string"}}
               "required" ["path" "content"]}
              (fn [args] (str "(would write " (get args "path") ")"))
              :safe false)
   (make-tool "eval_scheme" "Evaluate code (UNSAFE, denylist-sandboxed)."
              {"type" "object"
               "properties" {"code" {"type" "string"}}
               "required" ["code"]}
              (fn [_] "[error] eval not implemented in port")
              :safe false)])
