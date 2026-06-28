;;; Directory Local Variables — Clojure dev tooling for sajure.    -*- no-byte-compile: t -*-
;;;
;;; Stack (install source):
;;;   clojure-mode + extra-font-locking ... FreeBSD pkg (clojure-mode.el 5.21)
;;;   CIDER / nREPL ...................... MELPA   (eval, REPL, stepping debugger)
;;;   clojure-lsp + lsp-mode ............. MELPA + JVM dep (:refactor alias)
;;;                                        — navigation, project-wide RENAME, refactor
;;;   clj-refactor ...................... MELPA   (refactor-nrepl backend, in :repl)
;;;
;;; cider-jack-in uses the :repl alias (nrepl + cider-nrepl + refactor-nrepl +
;;; rebel-readline). Time-travel: `clojure -M:flowstorm:repl` then M-x cider-connect.
;;; Project-wide rename (the cost-of-change lesson): `gmake rename FROM=.. TO=..`
;;; (clojure-lsp), or CIDER `cljr-rename-file-or-dir`, or lsp `lsp-rename`.

((nil
  . ((indent-tabs-mode . nil)))

 (clojure-mode
  . ((cider-preferred-build-tool . clojure-cli)
     (cider-clojure-cli-aliases   . ":repl")
     ;; lsp + CIDER coexistence: lsp owns xref/rename/refactor, CIDER owns eval.
     (lsp-enable-completion-at-point . nil)   ; CIDER completion wins at the REPL
     (lsp-enable-indentation         . nil)   ; clojure-mode/cljfmt own indentation
     (lsp-enable-on-type-formatting  . nil)
     (cljr-warn-on-eval              . nil)
     ;; Auto-start clojure-lsp IF lsp-mode is installed (no-op when only
     ;; clojure-mode is present — safe on a bare FreeBSD pkg setup).
     (eval . (when (and (require 'lsp-mode nil t) (buffer-file-name))
               (lsp-deferred))))))
