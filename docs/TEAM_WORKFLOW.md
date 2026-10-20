# Two-person workflow

## Branches

| Person | Branch | Contents |
|---|---|---|
| A | `feature/lock-and-core` | lock, Section, DocumentStore, SelfTests, LockDemo |
| A | `feature/race-lab-engine` | RaceLab, LabService, LoadDemo |
| A | `feature/api` | ApiHandler, Broadcaster, StaticHandler, Server |
| B | `feature/frontend` | static/index.html, style.css, app.js |
| B | `feature/race-lab-ui` | lab.html, lab.js, welcome guide, presence UI |
| B | `feature/testing-sharing` | StressTest, HttpLoadTest, CI, public-link instructions (ngrok / Cloudflare), optional Dockerfile |

`main` must always build and pass `test.bat`. Merge through Pull Requests and let the other person review.

## Suggested commit history (so both contributions are visible)

**Person A**
1. `Add ReaderPriorityLock with mutex, wrt and readCount`
2. `Add LockDemo to show reader priority in the console`
3. `Add Section with edit claim, lease and commit`
   (later) `Add RaceLab engine and LoadDemo narrated demo`
4. `Add DocumentStore with activity log and file persistence`
5. `Add REST API and server-sent events`
6. `Add SelfTests`

**Person B**
1. `Add repository skeleton, README and VS Code settings`
2. `Add page layout and section cards`
3. `Connect UI to the API and live events`
4. `Add slow-motion controls and activity log panel`
5. `Add StressTest and HttpLoadTest`
6. `Add CI workflow and public-link instructions`
7. `Add Race Lab page`

## Handover points

* Day 1: agree on the API table in the README.
* When the API works, A tells B and B switches from mock data to real calls.
* Before the demo, B deploys and A reviews; both rehearse the demo script in the README.

## Report split

A: theory, algorithm, pseudocode, code explanation. B: introduction, testing and results, screenshots, formatting. Both: abstract, conclusion, references.
