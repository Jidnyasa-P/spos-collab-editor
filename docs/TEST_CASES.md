# Test cases

"Automated" rows are checked by `SelfTests` / `StressTest` / `HttpLoadTest` / `loaddemo`. "Manual" rows are done in the browser.
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
| TC21 | Automated | Race Lab without a lock | Updates are lost (counter below expected) | | |
| TC22 | Automated | Race Lab with the lock | Counter equals expected, mirror equal, 0 lost | | |
| TC23 | Automated | Race Lab readers with the lock | 0 inconsistent reads | | |
| TC15-18 | Automated | Name validation, JSON escaping, form parsing, data-file escaping | Correct output | | |
| TC19, TC20 | Automated | Save and reload; reset | Text and version survive; reset restores seed | | |
| TC24 | Automated | `StressTest` part 1 (unprotected ints) | 0 violations, `a == b` | | |
| TC25 | Automated | `StressTest` part 2 | commits = final version | | |
| TC26 | Automated | `HttpLoadTest` | version growth = successful commits | | |
| TC27 | Automated | `loaddemo` scene 4: eight simultaneous claims | exactly 1 granted, 7 refused | | |
| TC28 | Manual | Three browser windows read together | All see all sections, names in "People here" | | |
| TC29 | Manual | Asha edits a section; Ravi presses Edit on it | Ravi sees "Locked by Asha" and is refused | | |
| TC30 | Manual | Asha and Ravi edit different sections | Both succeed | | |
| TC31 | Manual | Asha saves | Others update (live or within about a second), version +1 | | |
| TC32 | Manual | Asha closes the tab while editing; wait 60 s | Lease expires, Ravi can claim | | |
| TC33 | Manual | Slow motion: reads 4000 ms, reload two windows | Teal reader dots appear | | |
| TC34 | Manual | Slow motion: saves 3000 ms, save | Rail turns solid amber, readers wait | | |
| TC35 | Manual | Race Lab, defaults | Left side loses updates; right side reaches the expected counter | | |
| TC36 | Manual | Race Lab with Busy readers | Writers wait several seconds on the right | | |
| TC37 | Manual | Text with quotes, `<`, emoji, new lines | Shown correctly, no broken page | | |
| TC38 | Manual | Name `<b>` or 21 characters | Rejected with a message | | |
| TC39 | Manual | Open through a Cloudflare quick tunnel | Works; header says Reconnecting; updates arrive by polling | | |
| TC40 | Manual | Open on a phone and in dark mode | No sideways scrolling; readable | | |
