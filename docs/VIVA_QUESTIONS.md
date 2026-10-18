# Viva practice questions

1. **What problem does the project solve?** Many users read a shared document while at most one changes a given section; this is the Readers-Writers problem.
2. **Why reader priority?** The requirement says reading must not be blocked unless someone is actually writing. Readers overtake a waiting writer.
3. **Explain `mutex`, `wrt` and `readCount`.** `mutex` protects the counter, `wrt` gives exclusive access, `readCount` is the number of readers inside. The first reader takes `wrt`, the last one releases it.
4. **What is the drawback?** Writer starvation. We time out with `503` and show it with the Slow motion sliders.
5. **How would you fix starvation?** A writer-priority or fair variant, for example a queue or `ReentrantReadWriteLock` in fair mode.
6. **Why semaphores and not `ReentrantLock`?** The first reader acquires `wrt` and a different thread, the last reader, releases it. Semaphores have no owner thread; reentrant locks do.
7. **Why is the lock per section?** Edits to different sections do not conflict, so more things happen at once.
8. **Why a separate edit claim and write lock?** Typing takes minutes; holding the write lock that long would freeze every reader. The claim is cheap and expires; the write lock covers only the instant of the change.
9. **What stops a closed tab from locking a section forever?** The 60-second lease, renewed by a heartbeat.
10. **How do you know there are no race conditions?** `StressTest` part 1 uses unprotected integers that must stay equal; part 2 checks commits equal the final version; `HttpLoadTest` checks it over HTTP.
11. **How do other users see changes live?** Server-Sent Events push `doc` and `status` events and the browser refetches.
12. **Is there a deadlock risk?** Lock order is always write lock, then the section monitor. No code path takes them in the other order.
13. **What are the limits?** Section-level only, in-memory, no login, single server.
