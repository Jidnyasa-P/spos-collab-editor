# Test cases

"Automated" rows are checked by `SelfTests` / `StressTest` / `HttpLoadTest`. "Manual" rows are done in the browser.
Fill the **Actual** and **Status** columns from your own run and keep a screenshot for each manual case.

| ID | Type | Scenario | Expected | Actual | Status |
|---|---|---|---|---|---|
| TC1 | Automated | Six readers enter the lock together | All six inside at once | | |
| TC2 | Automated | Reader arrives while a writer is waiting | Reader enters immediately (reader priority) | | |
| TC2b/c | Automated | Writer waits, then enters after the last reader leaves | Writer enters only when no reader remains | | |
| TC3 | Automated | Reader arrives while a writer is inside | Reader waits until the writer leaves | | |
| TC4 | Automated | Writer waits while a reader stays | Writer times out instead of hanging | | |
| TC5 | Automated | Claim a free section | Granted | | |
| TC6 | Automated | Second user claims a held section | Denied, holder named | | |
| TC7 | Automated | Claims on different sections | Independent | | |
| TC8, TC9 | Automated | Commit without a claim / by another user | Forbidden | | |
| TC10 | Automated | Valid commit | Text changes, version +1, claim released | | |
| TC11 | Automated | Lease expires | Other user can claim; expired user cannot commit | | |
| TC12 | Automated | Heartbeat renew | Claim stays alive | | |
| TC13 | Automated | Commit while a reader is inside | Waits, then succeeds | | |
| TC14 | Automated | Readers never leave | Commit answers BUSY | | |
| TC15-18 | Automated | Name validation, JSON escaping, form parsing, data-file escaping | Correct output | | |
| TC19, TC20 | Automated | Save and reload; reset | Text and version survive; reset restores seed | | |
| TC21 | Automated | `StressTest` part 1 | 0 violations, `a == b` | | |
| TC22 | Automated | `StressTest` part 2 | commits = final version | | |
| TC23 | Automated | `HttpLoadTest` | version growth = successful commits | | |
| TC24 | Manual | Three browser windows read together | All see all sections | | |
| TC25 | Manual | Asha edits a section; Ravi presses Edit on it | Ravi sees "Asha is editing" and is refused | | |
| TC26 | Manual | Asha and Ravi edit different sections | Both succeed | | |
| TC27 | Manual | Asha saves | Others update live, version +1 | | |
| TC28 | Manual | Asha closes the tab while editing; wait 60 s | Lease expires, Ravi can claim | | |
| TC29 | Manual | Read hold 4000 ms, reload two windows | Teal reader dots appear | | |
| TC30 | Manual | Save hold 3000 ms, save | Rail turns solid amber, readers wait | | |
| TC31 | Manual | Text with quotes, `<`, emoji, new lines | Shown correctly, no broken page | | |
| TC32 | Manual | Name `<b>` or 21 characters | Rejected with a message | | |
| TC33 | Manual | Stop the server, then restart it | Page shows Reconnecting, then Live | | |
| TC34 | Manual | Open on a phone | No sideways scrolling | | |
