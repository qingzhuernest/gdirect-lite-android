# Public download verification

Verified on 2026-11-09 with the Windows Java worker's actual `publicInfo` and `public` operations, without an account token:

- Public sample: `https://drive.google.com/file/d/18cpd_3kqi6SPsIVMKklmd3EGgE_sDFFC/view`
- Probe returned `sample.pdf`, 1,273,827 bytes. Full download completed and passed the core's length checks.
- Unavailable sample `1F9NCSMf7MUMdT6aALelPtil3er4LYdub` returned HTTP 404. This now produces an explicit unavailable/not-public message instead of a generic Java-core failure.
- The application used its existing Java Google-only connection selection, without an account token or an application-configured HTTP proxy. This is a result on the current machine/network, not a claim of accessibility on every network or proof that system VPN/TUN was bypassed.

Public file owners can change access or delete a file later. Recheck before treating any external sample as an acceptance fixture. A 404 does not establish whether a file is deleted or simply inaccessible.

Regression checks cover 401/403/404/429/503 and login/confirmation HTML, with fixed messages that do not return request URLs, file identifiers, response bodies or raw exceptions. The Java suite has 206 tests; the staged Windows package passes 21 checks. Full Android checks require an Android SDK and remain separate from this validation.
