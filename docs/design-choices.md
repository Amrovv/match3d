# Design choices

Every major decision that had a real alternative, with what was rejected and what the choice costs.

Only decisions already taken and built appear here. New entries go at the top of their section as they land. What each component actually does is in [project-extended-description.md](project-extended-description.md).

The cost line is not optional. A decision with no stated cost is either trivial or dishonest.

## Contents

1. [Matching under contention](#matching-under-contention)
2. [Selecting a lobby](#selecting-a-lobby)
3. [Fairness and waiting](#fairness-and-waiting)
4. [Indexing players by skill](#indexing-players-by-skill)
5. [The domain model](#the-domain-model)
6. [Repository and build](#repository-and-build)

## Matching under contention

### Optimistic selection, exclusive commit

**Options.** One lock held across the whole pass, a lock per structure, or an unsynchronised selection verified under a lock.

**Chosen.** Verify. A lock per structure closes nothing, because the race lives in the gaps between individually atomic operations: the unit of safety is a span, not an object. That leaves a span, and the span that covers everything is the whole pass, which is also where all the time goes. Selection seeds a merge over every bucket in the window; the commit is ten removals. Locking selection makes eight workers behave as one.

The two problems with an unsynchronised selection are that it reads live views and that its result is stale by commit time. Neither says no other worker may select at once. Both say a selection cannot be trusted when it is committed, which is a verification requirement, and verifying is cheap where excluding is not.

**Cost.** Wasted work under contention, and a second correctness argument to hold: the structures are safe because the lock covers every mutation, and the outcome is correct because the commit verifies. Neither alone is enough.

### Nothing is removed unless all ten verify

**Options.** Claim and remove members as they are confirmed, or verify all ten and remove none unless all survive.

**Chosen.** All or nothing. Incremental claiming leaves a worker that then fails holding players who are in no lobby and in no queue. That deletes a player where the original race merely duplicated one, and it is only safe with a release step that a dying worker never performs.

**Cost.** A worker can do a full selection and commit nothing.

### The anchor is claimed, the members are not

**Options.** Leave the anchor in the index while a worker builds around them, or remove them for the duration of the pass.

**Chosen.** Claim. Anchors are the longest waiters and the merge offers those first, so every worker's candidate draw began with the other workers' anchors. Measured over ten rounds at maximum contention, 464 passes were abandoned because the anchor had been recruited elsewhere, and the retry path never executed once.

**Cost.** The compensating action that the members deliberately avoid. An unsettled anchor is in neither structure, so any abnormal exit has to put them back, and a process that dies mid pass loses them until the queue redelivers. Measured throughput is unchanged, so this buys an invariant rather than speed: a queued player is in exactly one place at any moment, which is also what party matching will need.

### A retry budget of the seats standing

**Options.** Abandon the pass when a member is taken, retry without limit, or retry a bounded number of times.

**Chosen.** Bounded, and the bound is the number of seats still standing at the first failed verify, so a nearly complete lobby is worth more persistence than a bare one. It is set once. Recomputing it from a later, fuller selection lets it grow, and the loop stops terminating under exactly the contention it exists for.

Retrying is for contention, where another worker committed and the index has changed. A refill that finds nobody is starvation, where nothing has changed and asking again microseconds later returns the same answer, so that cools instead.

**Cost.** A selection that starts at three seats and grows to nine keeps the budget of three, so a lobby that became valuable mid pass is not credited for it. Termination is worth more than the credit.

### A retry resumes the walk rather than re-seeding it

**Options.** Rebuild the candidate merge for each attempt, or carry the cursor and the consent state across attempts.

**Chosen.** Carry them. Seeding touches every occupied rating in the window and is the expensive part of a pass; seating a player is a logarithm in the bucket count. Rebuilding to save nine constant time consent checks made a retry cost what a whole fresh pass costs, which is why the first version of the retry loop measured no better than abandoning.

20k players, normal spread, eight workers, fifteen repeats: 399 lobbies in 100ms rebuilding, 988 resuming.

**Cost.** A resumed walk cannot reconsider. Dropping a member widens the reach, so a candidate rejected earlier might be acceptable now, and the cursor has already passed them. A resumed pass can therefore fail to fill where a fresh one would have succeeded. Ratings that become occupied after seeding are also invisible for the rest of the pass, which is consistent with the pass already fixing its own instant.

## Selecting a lobby

### Anchor driven, not group driven

**Options.** Sweep the whole rating axis for valid groups wherever they exist, solve for a global optimum, or build around one anchor.

**Chosen.** Anchor driven, anchored on the longest waiting player. A sweep costs a term in the number of queued players, which is exactly what the skill index exists to avoid, and it demotes fairness to a tiebreak. The global optimum is a partition into cliques, so NP hard.

**Cost.** Greedy. It can miss a valid lobby elsewhere in the queue. It wins on having one invariant statable in a sentence, the engine always works on whoever has waited longest, not on match quality. Multi anchor batching is the recorded scaling follow up.

### Consent is mutual and pairwise

**Options.** The anchor's window alone, mutual between anchor and candidate, or mutual across every pair.

**Chosen.** Every pair. Anchor only would make the radius mean where to look rather than a lobby a player would accept, letting a ten minute anchor conscript someone who queued five seconds ago. Star shaped consent lets two members at opposite edges of the anchor's window sit outside each other's.

This looked like it would force backtracking and nearly forced a redesign away from anchors. It collapses: since an interval is contiguous, the pairwise condition is equivalent to a per player one, every member's interval must cover the group's rating span. Four running numbers carry it, so a candidate costs constant time.

**Cost.** None worth naming. The strict choice turned out to be the cheap one.

### Remaining seats go to the longest waiting

**Options.** Closest rating first, or longest waiting first.

**Chosen.** Longest waiting, consistent with what the heap is for.

**Cost.** A lobby fills with the other stragglers, who are precisely the players furthest from the rating mass. Fairness and match quality pull against each other here. The radius caps how bad it gets.

### One attempt per call

**Options.** Loop internally until something forms, or return after one attempt.

**Chosen.** One attempt. Every call either seats ten players or cools one anchor, so both outcomes shrink the heap and a caller loop terminates without tracking what it tried. Looping internally would hold milestone 2's lock across an unbounded number of attempts.

**Cost.** The matcher alone does nothing. Cadence belongs to the caller.

**Amended, 10 September 2026.** Still one anchor per call, but no longer one attempt: a pass that loses a member to another worker refills and verifies again, within a budget. The termination argument survives, since the budget is set once and decremented, and every call still shrinks the heap.

### A fixed cooldown for failed anchors

**Options.** Reinsert immediately, a fixed period, or a period growing with each failure.

**Chosen.** Fixed at 10 seconds. Immediate reinsertion fails outright, since the heap orders by queue time and reinsertion does not change it, so the same player returns on the very next call. Growing cooldowns point the wrong way: the repeat failer is the player furthest from the rating mass, precisely who the heap protects, and the moment they become matchable is uncorrelated with their failure count. Queue time is untouched while they sit out, so they return to the front with a wider radius.

**Cost.** 10 seconds is a constant with nothing behind it, and no failure count is stored, so a player failing repeatedly goes unnoticed.

**Amended, 10 September 2026.** The period is a constructor argument now, defaulting to the same ten seconds. It is policy rather than physics, and a cooldown longer than a measurement window makes the measurement about the cooldown. Cooling also records its cause, since an anchor with no lobby available and an anchor that lost too many races are different facts about the queue wearing the same treatment.

### Cooling bars anchoring, not matching

**Options.** Pull a cooling player out of the index too, or leave them recruitable.

**Chosen.** Leave them in. Pulling them out would sit them out of the exact window in which a match might have existed.

**Cost.** A recruited player must then also be removed from the cooldown queue, or the drain hands an already matched player back to the heap, where they can anchor a lobby drawn from an index they are no longer in. Found by reasoning rather than by a failing test, and exactly one test now stands between it and a return.

### A k way merge rather than sorting the window

**Options.** Materialise the window and sort, or merge across already ordered bucket heads.

**Chosen.** Merge. Sorting costs a term over every candidate in the window to seat nine. The merge holds bucket indices rather than head players, because the heads and cursor lists run in parallel and reordering either would make an index stop naming its own bucket.

**Cost.** Seeding is eager, touching one player per occupied rating in the window whether the caller draws any or not. Bounded by window width, never by queue size.

### An iterator produces, a stream consumes

**Options.** Build the merged sequence and return it, or produce it one player at a time behind a stream.

**Chosen.** An iterator inside, a stream outside. Merging is inherently a pull: look at the heads, take the earliest, refill only that bucket. An iterator says that directly, where a stream operator would have to fake it. But an iterator is a poor thing to hand a caller, so it is wrapped as a sequential stream and the caller never sees it.

The result is that the ordered sequence exists nowhere in memory. It is produced one player at a time on demand, and a caller taking ten pays for ten draws. The chain is a window of thousands of players, then a stream of a few hundred bucket references, then one player at a time, and only the last step is ever repeated.

The stream is sequential deliberately. Parallel would destroy the ordering the class exists to provide.

**Cost.** Two hazards the type system does not express. The stream is single use, and it draws from live views, so the index must not be mutated mid draw. Both are documented and both have tests.

## Fairness and waiting

### A saturating exponential window

**Options.** Linear, stepped, or a flattening curve.

**Chosen.** `W(t) = 2500 - (2500 - 50) * e^(-0.00142 * t)`. Growth is fastest early, where a small concession buys the most, and flattens once a wider window mostly recruits players who make the lobby worse. Linear has this exactly backwards. Stepped is a discontinuity, so two players a second apart get very different windows, and every boundary is an arbitrary number to defend. The maximum is an asymptote rather than a cap, so nothing decides what happens at the ceiling. The decay constant is set so a minute of waiting gives a radius of 250, a tenth of the domain, and exactly one test pins it.

**Cost.** Harder to reason about at a glance than a line, and the constants are defensible rather than derived. There is no player behaviour data behind them.

### A queue time in the future counts as no wait

**Options.** Let the widening function's rejection of a negative wait propagate, or clamp at the matcher.

**Chosen.** Clamp at the matcher. Intake stamps the queue time on one machine and the engine reads it on another, so modest clock skew puts a freshly joined player slightly ahead of now. That is the same player state as a wait of zero, and crashing on it would make the engine hostage to two clocks agreeing. The widening function still rejects a negative wait, since reaching it means a caller computed one rather than a clock disagreeing.

**Cost.** A skewed clock is silently absorbed rather than surfaced, so a badly wrong clock looks like a queue full of new arrivals. Nothing measures skew yet.

### No clock inside the engine

**Options.** Read a clock where it is needed, or take the current instant as a parameter throughout.

**Chosen.** Parameter. The curve can then be tested at any point on it and the matcher at any instant, without waiting for one.

**Cost.** The instant is threaded through several signatures that would not otherwise need it.

### A hand rolled indexed heap

**Options.** `PriorityQueue`, or a hand written binary heap with a map from player id to array index.

**Chosen.** Hand rolled. Ten players leave the middle of the heap every time a lobby forms, and `PriorityQueue.remove(Object)` is a linear scan. The map makes it logarithmic. This is where the hand rolling budget was spent, rather than on the tree.

**Cost.** Every movement inside the array must go through the swap helper, or the map and array drift apart and removal corrupts the heap silently. Carried by discipline and tests.

### The cooldown queue is a library PriorityQueue

**Options.** Reuse the indexed heap, or take `PriorityQueue`.

**Chosen.** `PriorityQueue`. Arbitrary removal does happen here, since a recruited player must leave, so the original justification was wrong. It survives for a different reason: the queue holds failed anchors only, so it is small, and removal runs once per lobby rather than once per queued player. Removal is by predicate, since the pending record's equality covers a ready instant the caller does not know.

**Cost.** A linear scan, accepted on the size of the collection and the frequency of the operation.

## Indexing players by skill

### TreeMap rather than a hand rolled balanced tree

**Options.** Hand roll an AVL or red black tree, or build on `TreeMap`.

**Chosen.** `TreeMap`. An AVL was written first, with its own tests and a benchmark harness, then discarded: review found a live bug, where the duplicate id check compared ids along a descent chosen by rating, so the same id at a different rating inserts twice.

**Cost.** The interview answer is now about why `TreeMap` was chosen rather than about a tree that was built. Weaker looking, and honest. No benchmark numbers survive, since the harness measured a lazy view against a full materialisation and was not worth repairing.

**Reversed, 10 September 2026, to `ConcurrentSkipListMap`.** Matching now selects candidates without holding the lock, which means iterating a window while another worker commits into it. A `TreeMap` iterator is fail fast, so workers were surviving `ConcurrentModificationException` mid pass, and fail fast is documented as best effort, so the silent corruption behind it could not be ruled out either. Copying the window instead would destroy the laziness the whole index exists for, and taking the lock during selection would serialise the expensive part of a pass.

A skip list is still an ordered map implementing `NavigableMap`, so range queries, `subMap` and the 5000 entry bound are all unchanged. What changes is that iteration is weakly consistent instead of fail fast.

**Cost.** A probabilistic bound rather than a worst case one, and the structure is no longer a balanced tree, so the reason for choosing an ordered structure has to be stated as order versus a hash map rather than as a tree versus anything. Weakly consistent also means a drawn player may already have left, which is why the commit verifies rather than trusting the draw.

### One bucket per exact rating

**Options.** One node per player, one per exact rating, or banded buckets spanning a range.

**Chosen.** One per exact rating. Ratings run 1 to 5000, so the index is bounded at 5000 entries however many players queue. Banding would cut the buckets a query touches from about 201 to about 9 on a window of plus or minus 100, but a banded bucket holds players outside the window, so edges need filtering, and rating order inside a bucket is lost.

**Cost.** A query touches more buckets than a banded index would.

### Buckets are LinkedHashSet

**Options.** `HashSet` and sort when needed, `TreeSet` by queue time, or `LinkedHashSet`.

**Chosen.** `LinkedHashSet`. Arrival order is already wait time order, so the order needs preserving, not computing.

**Cost.** The guarantee is implicit. It holds only because insertion happens to be chronological, and nothing in the type system says so.

**Reversed, 10 September 2026, to `ConcurrentSkipListSet` ordered by wait time.** The same concurrent iteration problem as the map above, and a bucket iterator is live for far longer than the map's, since it stays open for the whole candidate walk.

The implicit ordering cost disappears with it. The order is now a property of the type, which matters once players arrive over a message queue and delivery order stops being join order.

**Cost.** Bucket operations become O(log n_b) rather than O(1), where n_b is the players at one exact rating. A hand rolled concurrent linked hash set would keep the O(1), and was rejected: the prize is roughly sixteen comparisons on a crowded bucket, the risk is a memory visibility bug that no single threaded test can catch, and no benchmark exists yet to say the bucket is hot at all.

Equality inside a bucket is now the comparator's, queue time then id, rather than `Player.equals`, which is the id alone. That is safe only because removal looks the player up in the id map first and hands the bucket the object it filed.

### Empty buckets are deleted

**Options.** Retain for reuse, or delete.

**Chosen.** Delete. The memory argument is weak, since buckets are bounded at 5000. The real one is query cost: the matcher seeds every bucket in the window, so retained dead buckets would make that count mean ratings ever seen rather than ratings currently occupied, and an empty queue would cost as much as a busy one.

**Cost.** A hot rating that empties and refills repeatedly pays a removal, a reinsertion, and a fresh allocation each cycle.

### Range queries hand back buckets, not players

**Options.** A flat stream of players, or a stream of the buckets themselves.

**Chosen.** Buckets. The k way merge needs the bucket boundaries to work at all, and flattening destroys exactly the per bucket ordering it merges on. No second method was added, since the matcher is the only consumer and it always wants buckets.

Buckets leave wrapped in an unmodifiable view, which is a constant time wrapper rather than a copy. An eager copy was rejected twice over: it guarantees no ordering, which would silently destroy the wait time order the buckets exist to preserve, and copying a window to seat ten players is the cost the whole design avoids.

**Cost.** The caller sees an internal shape, so the index and the merge are coupled to each other.

### Range queries are lazy

**Options.** A materialised list, an iterator, or a stream of live bucket views.

**Chosen.** A stream. This is the decision the rest of the index hangs on. An anchor five minutes into the queue accepts a radius of 899, so the window spans roughly 1800 ratings and can hold thousands of queued players. A lobby seats ten. Returning a list means building every one of those to hand back ten, which throws away the entire advantage of indexing by rating in the first place.

A stream rather than an iterator because it composes: `flatMap`, `limit` and `takeWhile` come free, which is how tests flatten buckets back to players in one line and how the matcher stops early without a loop tracking its own count.

**Cost.** The buckets handed out are live views, not copies. Since both levels became skip lists the draw no longer throws when another thread mutates the index, but it is still weakly consistent: a player drawn from it may already have left. The commit verifies for exactly that reason.

### No side index by player id

**Options.** A map from id to player beside the tree, or the rating keyed index alone.

**Chosen.** No side index. Uniqueness becomes a rule upstream: a queued player must leave before queueing again.

**Cost.** `SkillIndex` is not authoritative about its own contents. The same id inserted at two different ratings lands in two buckets and both inserts succeed, structurally the same defect that ended the AVL attempt. An upstream check is a policy, not a guarantee, and two threads can interleave through it. Due for revisit when the worker pool lands.

**Reversed, 10 September 2026.** A map from id to player now sits beside the ordered map and is the authority on what is queued. The revisit was forced by the concurrency fix, which has to ask whether an id is still queued at all, and a rating keyed index can only answer whether a player is queued at a given rating.

Three consequences. Insert refuses a duplicate id whatever rating a second join carries, which closes the orphan entry above. Remove takes the id as the address and ignores the rating on the argument, so a caller holding a player whose rating has since changed still removes the right entry. The player count is the map's size, so it cannot drift from the contents.

**Cost.** Two structures that must stay in step, the same discipline the fairness heap already carries with its position map, and it fails silently when broken. Removing with a stale rating used to return false; it now succeeds, which reads like losing a check but is not, since nothing was reading that false.

## The domain model

### Records and immutability

**Options.** Mutable objects updated in place, or immutable records replaced on change.

**Chosen.** Records. Several structures, and soon several threads, hold the same player. A player whose fields change underneath a reader is a data race waiting for milestone 2, and mutating a rating in place would strand the player in the wrong bucket.

**Cost.** A change means constructing a new player and re inserting them, so the structures are updated rather than the object.

### Identity is the id alone

**Options.** The record's generated equality over all three components, or an override on id.

**Chosen.** The override. A player reconstructed on the far side of a message queue at milestone 4 will not carry a bit identical queue time, and removal must still find them.

**Cost.** Equality disagrees with the record's own components, which surprises a reader expecting generated behaviour. Two players with the same id and different ratings compare equal despite occupying different buckets, which is the hole the missing side index leaves open.

## Repository and build

### Four modules rather than one

**Options.** One module and split later, or four from the start.

**Chosen.** Four. `matchmaking-core` carries no framework or network dependency, enforced by the module boundary rather than by discipline. The dependency arrow points inward and nothing depends on a service.

**Cost.** Four build files to keep in step, and a tree that looks disproportionate to what it holds.

### CI from the first commit

**Options.** Build the pipeline at the end, or before any real logic.

**Chosen.** First, against a placeholder test. The branch to pull request to green check loop becomes the working habit, and a review process bolted on afterwards is visible in the commit history.

**Cost.** The pipeline proves very little for the first few commits.

### Feature branches and squash merges

**Options.** Direct commits with occasional pull requests, or branch protection with no exceptions.

**Chosen.** Branch protection, no direct pushes, CI green before merge, squash merge so `main` reads as one commit per unit of work.

**Cost.** Process overhead disproportionate to a solo project, and a standing temptation to open a pull request for its own sake. Trivial changes are batched instead.
