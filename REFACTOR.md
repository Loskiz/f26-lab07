# REFACTOR.md

One section per milestone. Fill each one in as you go, in order.

Milestone 1 is written in two sittings, the pin before the refactor and the
rest after. A pin written afterwards is worth nothing, and a TA will ask.

Keep it short and specific. Point at methods, call sites, and test names.

---

## Milestone 1: Direct a refactor, characterization first

### The pin (write this section before you direct the refactor)

**The pin (planned).** Proposed file:
`src/test/java/edu/cmu/cs214/scheduling/workflow/BookingWorkflowCharacterizationTest.java`;
proposed test: `cancellingMiddleOccurrenceCancelsItAndLaterOccurrencesOnly`.
It will pin `BookingWorkflow.cancel`: cancelling the middle occurrence of a
three-week series leaves the first active, cancels the second and third, and
adds exactly two cancellation notifications in occurrence order, one for each
newly cancelled occurrence. The shipped 35 tests pass; this additional test has
not yet been written or run. It and this pin section must be committed before
the refactor, without editing or deleting any shipped test method.

**Why that one, and does a shipped test already cover it?** The strongest gaps
cluster around recurring behavior: partial submission, the scope of
cancellation, series-wide pricing, and endpoint conflicts. Recurrence makes a
single booking ID stand for decisions about an entire series. Forward
cancellation is the preferred pin because it captures a substantial business
decision through stored state and notifications. Endpoint conflicts are another
strong candidate, especially if overlap validation is shared during extraction,
but forward cancellation is easier to explain as a choice between cancelling
one occurrence, the remaining series, or the whole series.

I inspected the shipped tests. In `BookingWorkflowTest`,
`recurringCancelReleasesTheOccurrence` cancels only the last occurrence and
asserts that cancellation succeeds and that occurrence is cancelled. That case
cannot distinguish cancelling one occurrence from cancelling it and everything
after it. No shipped test selects a middle occurrence and checks the earlier
occurrence, later occurrences, and cancellation notifications together.

**What a regeneration would do differently here.** Regenerating from "a room
booking workflow" would require choosing the scope of recurring cancellation
again. Since `cancel` accepts one booking ID, a fresh implementation would
plausibly cancel only that occurrence. The current implementation instead
cancels that occurrence and all later active occurrences in the same series,
while preserving earlier ones. The pin will record this existing behavior
without claiming it is the only desirable policy.

### The directive

**The refactor and the exact directive.** Name the refactor (one from the menu
in the handout) and paste the directive you gave the agent, including the scope
you set, meaning which files and packages were in bounds, which were not, and
one line on why the boundary sits where it does.

### The result

**The diff and the suite.** How you are showing the diff to the TA (a commit,
`git diff`, a branch), and the totals line (the shipped count plus your pin,
all green).

**What did NOT change: behavior and files.** The observable behavior you
checked is still the same, including anything that surprised you while reading.
Which files outside the scope are untouched, and how you verified that rather
than assumed it. If the agent reached outside the directive, say where and what
you did about it.

**One thing the agent changed that you had to look at twice.** Something you
checked line by line before accepting. If there was nothing, say how carefully
you read the diff.

### The closing explanation

**Refactor or regenerate?** Argue whether regenerating `BookingWorkflow` from scratch
would have been the better call, using the lecture's four questions (test
coverage, code age, spec quality, and reach). Be concrete about this codebase.

**What would flip your answer.** A condition about the artifact, not a feeling.

---

## Milestone 2: The pattern critique

Read `notify/`. It works and the outbox tests pass.

### The patterns present

List every design pattern you can name in that package. For each one, the class
or classes that carry it.

### The problem each one solves

For each pattern you listed, what would have to be true about the requirements
for that pattern to be the right call? One sentence each, not in terms of
"flexibility".

### Which of those problems exist here

For each pattern, does the problem it solves exist in this codebase? Point at
the code that settles it.

### The simpler structure

**Your proposal.** What replaces `notify/`. Sketch the classes and the one
method that matters.

**What stays the same.** The tested behavior it must still produce, named
precisely enough that a reader can check it against the shipped tests.

**What you would keep, if anything.** If you would keep one interface, say
which and why. "None of it" is a fine answer if you can defend it.

### What would bring each layer back

For at least two of the layers you would remove, what requirement, if it
arrived next sprint, would make that layer the right structure? Be specific
about the requirement, not about the pattern.

**Misuse or anti-pattern?** Say which this is and why the distinction matters.

---

## Milestone 3: The missing pattern

Read `pricing/`. Not coded, one sentence.

**The pattern.** Which one fits `PriceCalculator`, and the problem that makes
it fit. Name the problem.

**Would you apply it today?** Yes or no, one line, with the reason.
