# L1L5 SOS GPS

Emergency Android GPS that computes its own position from **GPS L1 C/A and L5 only**
(raw GNSS measurements). Works fully offline.

- 5+ satellites with L1+L5 → iono-free L1/L5 solution; otherwise L1 C/A only.
- Ephemeris is decoded from the satellites' L1 C/A nav message and saved, so later starts are quicker.
- SOS SMS / Share / Copy send coordinates + a map link.
- The phone's normal fix is shown underneath for comparison only.

Needs Android 10+, a phone with raw GNSS measurements (dual-frequency for L5) and
nav-message support (most Pixels; many Samsungs don't provide nav messages).

Build: push to GitHub → Actions → "Build APK" → download the artifact.
