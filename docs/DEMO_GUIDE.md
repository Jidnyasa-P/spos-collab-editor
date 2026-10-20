# Demo guide: showing that the lock prevents race conditions

Total time: about 6 minutes. Do the first part in the console, the second in the browser.

## Part 1: `loaddemo.bat` (console, about 3 minutes)

Open a terminal in the project folder and run `.\loaddemo.bat`. Start the server first (`.\run.bat` in another terminal) if you want scene 4.
The program waits for **Enter** between scenes so you can talk.

**Intro.** "A shared counter and a mirror copy. Four writers each add 1 to it five times, so the right answer is 20. Two readers keep checking
that the counter and its mirror agree. We slowed everything down so we can see it."

**Scene 1, no lock.** Lines starting with `!! LOST UPDATE` appear. Say: "Writer 4 read 0, but Writer 1 already made it 1, so Writer 4 writes 1 and
erases Writer 1's work. Nothing reported an error." Point at the result: final counter far below 20, `Updates lost` above 0, and `Inconsistent reads`
above 0 ("a reader saw the counter changed but not yet its mirror").

**Scene 2, with the lock.** Lines starting with `ok` appear one after another. Say: "Same job, same speed. Writers take turns because a writer must hold the write lock
from reading to the last assignment." Result: 20 of 20, 0 lost, 0 inconsistent. "Readers still shared the lock; the most inside together was 2."

**Scene 3, reader priority.** Walk through the timeline: Reader-1 is in, Writer-1 arrives and waits, Reader-2 and Reader-3 arrive *later* but go in first,
Writer-1 enters only after the last reader left, and Reader-4 had to wait for the writer.

**Scene 4, over HTTP.** Eight users press Edit on the same section at the same instant. Exactly one is granted. "This is mutual exclusion on the real server."

**Summary table.** Show NO LOCK versus WITH LOCK side by side.

## Part 2: the browser (about 3 minutes)

1. Open **Race Lab** (header link). Leave the defaults and press **Run both**. Talk while it runs: amber chips are writing, teal chips are reading, dashed chips are waiting.
   On the left the log fills with lost updates. On the right the counter climbs one by one. Read the verdict card at the end.
2. Tick **Busy readers** and run again. On the right, writers sit dashed (waiting) for seconds. "This is the cost of reader priority: writer starvation."
3. Go to the **Editor**. Open a second window with another name. Claim the same section from both windows: the second is refused and shows *Locked by*.
4. In the side panel press **Show a save**, then save in one window. The rail turns solid amber and readers wait. Press **Off** afterwards.

## Answers to expect

* *How do you know there are no race conditions?* We count them. The Race Lab and `loaddemo` count lost updates and inconsistent reads; with the lock both are zero,
  without it they are not. `StressTest` and `HttpLoadTest` check that successful commits equal the growth of the version numbers.
* *Is the output the same every time?* The with-lock numbers are. Without the lock the amount of damage varies from run to run, which is typical of race conditions.
* *Why does the locked run take longer?* Writers take turns. That is the price of correctness.
