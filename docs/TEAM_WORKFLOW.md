# Two-person workflow

## Branches

| Person | Branch | Contents |
|---|---|---|
| A | `feature/lock-and-core` | lock, Section, DocumentStore, SelfTests, LockDemo |
| A | `feature/api` | ApiHandler, Broadcaster, StaticHandler, Server |
| B | `feature/frontend` | static/index.html, style.css, app.js |
| B | `feature/testing-deploy` | StressTest, HttpLoadTest, Dockerfile, render.yaml, CI |

`main` must always build and pass `test.bat`. Merge through Pull Requests and let the other person review.

## Suggested commit history (so both contributions are visible)

**Person A**
1. `Add ReaderPriorityLock with mutex, wrt and readCount`
2. `Add LockDemo to show reader priority in the console`
3. `Add Section with edit claim, lease and commit`
4. `Add DocumentStore with activity log and file persistence`
5. `Add REST API and server-sent events`
6. `Add SelfTests`

**Person B**
1. `Add repository skeleton, README and VS Code settings`
2. `Add page layout and section cards`
3. `Connect UI to the API and live events`
4. `Add slow-motion controls and activity log panel`
5. `Add StressTest and HttpLoadTest`
6. `Add Dockerfile, render.yaml and CI workflow`

## Handover points

* Day 1: agree on the API table in the README.
* When the API works, A tells B and B switches from mock data to real calls.
* Before the demo, B deploys and A reviews; both rehearse the demo script in the README.

## Report split

A: theory, algorithm, pseudocode, code explanation. B: introduction, testing and results, screenshots, formatting. Both: abstract, conclusion, references.
