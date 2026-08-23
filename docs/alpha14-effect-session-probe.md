# ClearMic alpha14 — effect-session probe

Alpha14 preserves the working alpha13 IAudioService Binder monitor and focuses only on the external-session effect attachment failure (`NoiseSuppressor.create(sessionId) == null`).

For each eligible external capture session, the Shizuku UserService now tries, in order:

1. Public `NoiseSuppressor.create(sessionId)` wrapper.
2. Hidden `AudioEffect(type=EFFECT_TYPE_NS, uuid=NULL, sessionId)` through reflection so constructor failures are surfaced instead of swallowed.
3. Concrete NS implementation UUIDs returned by `AudioEffect.queryEffects()`, first with NS type + implementation UUID and then UUID-only selection.

The daemon records the available NS implementations, exact failure class/message, control/enabled state, implementation UUID, and the session pre-processing chain. Failed sessions are retried up to four times while active. Effects remain transient and are released when the external capture session ends or Game NS is disabled.
