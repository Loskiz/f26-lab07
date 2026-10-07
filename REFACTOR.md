# REFACTOR.md

One section per milestone. Fill each one in as you go, in order.

Milestone 1 is written in two sittings, the pin before the refactor and the
rest after. A pin written afterwards is worth nothing, and a TA will ask.

Keep it short and specific. Point at methods, call sites, and test names.

---

## Milestone 1: Direct a refactor, characterization first

### The pin (write this section before you direct the refactor)

**The pin.** File:
`src/test/java/edu/cmu/cs214/scheduling/workflow/BookingWorkflowCharacterizationTest.java`;
primary test: `cancellingMiddleOccurrenceCancelsItAndLaterOccurrencesOnly`.
It pins `BookingWorkflow.cancel`: cancelling the middle occurrence of a
three-week series leaves the first active, cancels the second and third, and
adds exactly two cancellation notifications in occurrence order, one for each
newly cancelled occurrence; another series remains active. Before any production
change, `mvn -B test` passed all 55 tests: the shipped 35 plus 20 characterization
cases. No existing test method or production file was edited. This test file and
pin section were committed and pushed as `82032b6` before the refactor.

Additional tests in the same file pin the boundary cases requested in the
directive:

| Test | Observable behavior pinned |
| --- | --- |
| `recurringConflictsIncludeTouchingEndpointsButExcludeGaps` (6 cases) | Both touching endpoints conflict; one-minute gaps do not; overlapping and identical slots are skipped without new bookings or notifications. |
| `recurringOccurrenceLimitsAreInclusive` (2 cases) | One and 26 occurrences are accepted, stored, numbered, and notified. |
| `invalidOccurrenceCountsRejectWithoutConsumingIds` (3 cases) | Negative, zero, and 27 occurrences reject with the existing messages and no side effects or consumed IDs. |
| `partiallyConflictingSeriesKeepsOriginalWeekNumbersAndNotifiesOnlyBookedSlots` | A blocked middle week is skipped; successful weeks retain indices 1 and 3, their descriptions, and ordered confirmations. |
| `fullySkippedSeriesConsumesASeriesIdButNoBookingIdAndSendsNothing` | A fully conflicted request rejects, reports its skipped slot, consumes a series ID, and sends nothing. |
| `recurringSubmissionIgnoresCancelledRoomBookings` | A cancelled booking no longer conflicts. |
| `recurringSubmissionAllowsAMemberAlreadyBookedInAnotherRoom` | Recurring requests allow the cross-room member conflict that regular requests reject. |
| `recurringValidationChecksMemberThenCapacityThenOccurrenceCount` | Rejection precedence stays member, capacity, then occurrence count. |
| `cancellingFirstOccurrenceCancelsTheWholeSeriesInDateOrder` | Cancelling the first occurrence releases the whole series and sends ordered cancellation messages. |
| `cancellationSkipsCancelledOccurrencesAndRepeatedCallsDoNotNotify` | Already cancelled occurrences are skipped; cancelling the same occurrence again returns false and sends nothing. |
| `everyOccurrenceIncludingCancelledOnesPricesTheRemainingActiveSeries` | Every occurrence ID, including cancelled ones, returns the remaining active series total; a fully cancelled series costs zero. |

**Why that one, and does a shipped test already cover it?** The strongest gaps
cluster around recurring behavior: partial submission, the scope of
cancellation, series-wide pricing, and endpoint conflicts. Recurrence makes a
single booking ID stand for decisions about an entire series. Forward
cancellation is the preferred pin because it captures a substantial business
decision through stored state and notifications. The additional endpoint tests
protect the differing overlap rules when validation is extracted into helpers.
The primary cancellation pin makes the choice between cancelling one
occurrence, the remaining series, or the whole series easy to explain.

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
while preserving earlier ones. The pin records this existing behavior
without claiming it is the only desirable policy.

### The directive

**The refactor and the exact directive.** Extract a class per booking type,
with shared methods for reused workflow operations. Remove the repeated type
conditionals from `submit`, `cancel`, `priceOf`, and `describe` while preserving
the public API and existing behavior.

The user gave this directive before characterization or production changes:

> Refactor the [BookingWorkflow.java](src/main/java/edu/cmu/cs214/scheduling/workflow/BookingWorkflow.java)  to be more readable, extract reused methods. Pin down the recurring booking behavior with characterization tests first (especially boundary cases), and then commit and push, and then execute the refactor; commit and push. Record this directive in the refactor.md, commit and push

**Scope.** Production changes are confined to
`src/main/java/edu/cmu/cs214/scheduling/workflow/`: `BookingWorkflow` and new
internal booking-type handlers and shared helper methods. Characterization
tests go in a new `BookingWorkflowCharacterizationTest` under the matching test
package. Existing test methods, `domain/`, `notify/`, `pricing/`, `reporting/`,
the build configuration, and milestones 2 and 3 are outside this change.
Milestone 1 in this file records the directive, pin, and verified result. This
boundary keeps the refactor focused on workflow structure and preserves its
collaborators and callers.

**Order.** Commit and push this directive first. Add the characterization tests
and update the pin section, run the suite against unchanged production code,
then commit and push that checkpoint. Only then perform the refactor, verify
the suite and scope, record the result, and commit and push again. Preserve the
current recurring endpoint, partial-success, cancellation, and pricing rules,
including behavior that differs from regular bookings.

### The result

**The diff and the suite.** The directive checkpoint is `e50ca32`; the
characterization checkpoint is `82032b6`, with production code unchanged. Show
the refactor with `git diff 82032b6..HEAD -- src/main/java/edu/cmu/cs214/scheduling/workflow`.
After extraction, `mvn -B clean test` rebuilt everything and reported:

```text
Tests run: 55, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

`BookingWorkflow` now delegates all four operations through one fixed map of
booking types to package-private `RegularBookingHandler`,
`RecurringBookingHandler`, and `BlockedBookingHandler`. Its size went from 296
to 91 lines. `BookingHandler` contains reused member/capacity validation, strict
overlap checks, notification publication, recipient fallback, and slot
description methods. `BookingWorkflow.roomName` removes the repeated room-name
fallback. No type switches remain anywhere in `workflow/`.

**What did NOT change: behavior and files.** All 35 shipped tests and the 20
characterization cases remain green. Recurring bookings still conflict at
touching endpoints, book only available weeks without renumbering them, allow
cross-room member conflicts, cancel forward within their own series, and price
all active occurrences even when queried through a cancelled occurrence.
Rejection messages and precedence, booking and series ID consumption, and the
tested notification text and order are preserved. The constructor and four
public method signatures remain unchanged.

The agent compared every test file byte for byte with `82032b6`, including the
new characterization file; all five are unchanged during the refactor. It also
compared every tracked file outside the production workflow package and
`REFACTOR.md` with that checkpoint. `domain/`, `notify/`, `pricing/`,
`reporting/`, the build configuration, and other tracked files are unchanged.
Milestones 2 and 3 were not edited. Production changes stayed within the recorded
scope; `git diff --check` passed.

**One thing the agent changed that needs careful review.** Extracting the room
conflict checks could accidentally make every type use the same endpoint rule.
The agent compared the original conditions with both extracted methods:
`BookingHandler.overlaps` retains strict `<` comparisons for regular bookings
and blocks, while `RecurringBookingHandler.roomHasRecurringConflict` retains
both `<=` comparisons. The endpoint and gap tests pass against both versions.
The recurring cancellation loop also retains the timestamp cutoff and skips
already cancelled occurrences; notifications still follow each state change.

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
