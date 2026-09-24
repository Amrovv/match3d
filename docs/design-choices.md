# Design choices

Every major decision that had a real alternative, with what was rejected and what the choice costs.

Only decisions already taken and built appear here. New entries go at the top of their section as they land. What each component actually does is in [project-extended-description.md](project-extended-description.md).

The cost line is not optional. A decision with no stated cost is either trivial or dishonest.

## Contents

1. [Persistence and recovery](#persistence-and-recovery)
2. [Two services over a queue](#two-services-over-a-queue)
3. [Parties and teams](#parties-and-teams)
4. [Matching under contention](#matching-under-contention)
5. [Selecting a lobby](#selecting-a-lobby)
6. [Fairness and waiting](#fairness-and-waiting)
7. [Indexing players by skill](#indexing-players-by-skill)
8. [The domain model](#the-domain-model)
9. [Repository and build](#repository-and-build)

## Persistence and recovery

### Estimates travel in the heartbeat

**Options.** Estimate each entry once when the engine accepts it and store the number in intake, ask matchmaking at status time, or send current waits by rating band on every heartbeat and estimate in intake.

**Chosen.** The heartbeat. Every ten seconds `MatchmakingAlive` carries the last hour's waits, grouped into bands of 100 by the rating each seat had when it waited, as a total and a count per band so bands combine exactly. `EntryAccepted` carries the rating the engine queued the entry at, a party's derived rating included, and intake averages the bands within five of it at status time. The estimate refreshes every ten seconds, and matchmaking runs one grouped query per beat instead of one per join, so its cost no longer grows with traffic. Asking at status time was rejected because it is a direct call between the services, and it makes every status poll load the one service that cannot scale out.

**Cost.** Bands make plus or minus 500 approximate at its edges, a 2537 player averaging 2000 to 3099. Intake keeps each entry's rating and the latest bands, two pieces of matchmaking's data it would otherwise not hold. The estimate counts matched players only, since leavers are never recorded.

### Matchmaking is down after three missed heartbeats

**Options.** Infer matchmaking's health from unanswered entries, call it on every status, or listen for a heartbeat on the queue.

**Chosen.** A heartbeat. Matchmaking sends `MatchmakingAlive` every ten seconds, and intake records when it arrived by its own clock, never the sender's, so the two machines' clocks never have to agree. Thirty seconds without one, three beats, and a queued player's status reads down with no estimate. Unanswered entries were rejected as a signal because silence may also mean the broker is down or matchmaking is behind.

**Cost.** A real outage shows up to 30 seconds late, and one slow beat after another can show down briefly while all is well. A first start reads down until the first beat arrives.

### Unconfirmed entries are sent again

**Options.** Rely on the player retrying their join, or have intake find entries matchmaking never confirmed.

**Chosen.** Find them. A sweeper runs every ten seconds on every intake copy and sends again any entry unconfirmed 30 seconds after it was last sent, with its original queue time. That closes the gap where a copy commits a join and dies before publishing it. The 30 seconds count from `sent_at` rather than `queued_at`, or a restart's requeue, whose queue times are old, would be sent again at once.

**Cost.** A lost join waits up to about 40 seconds. Several copies may send the same entry, and a broker outage makes every entry look unconfirmed, both harmless since the engine refuses a duplicate.

### Every entry is confirmed

**Options.** Treat silence after a join as success, or have matchmaking confirm every entry it accepts.

**Chosen.** Confirm. `EntryAccepted` joins `EntryRejected`, `EntryMatched`, `MatchEnded` and `MatchmakingStarted`, so every change to an entry is stated by a message rather than inferred from one not arriving. It is what lets intake tell an entry in the engine from one that never reached it.

**Cost.** One more message per join. A leave is still not confirmed, which is safe: if the entry was matched first, the `EntryMatched` still arrives and intake skips the player it no longer holds.

### A failed publish returns the lobby to the engine

**Options.** Log the failure and keep the match, return the entries and keep the match row, or return the entries and delete the match.

**Chosen.** Undo it. If `EntryMatched` cannot be published, the match and its seats are deleted in one transaction and each entry goes back into the engine through `enqueue`, a party rebuilt whole under its entry id, with its original queue time. The round of passes stops there, or it would form the same lobby again at once and spin while the broker is down.

**Cost.** A lobby waits for the next round, a second later, once the broker is back. A crash between saving the match and publishing it leaves a match that is never resulted, in both players' history; the restart requeue matches them again.

### A restart of matchmaking requeues everything intake holds

**Options.** A durable outbox in matchmaking's database, or intake sending every queued entry again when matchmaking restarts.

**Chosen.** Requeue from intake. The engine is memory only, so a restart loses everyone in it and any lobby formed but not yet published. Intake already holds who is queued, so when `MatchmakingStarted` arrives one intake copy sends every entry again with its original queue time, keeping each player's place and widened window. It travels on the queue `EntryMatched` uses, so a lobby published before the restart is seen first and its players are not requeued. An outbox was rejected because intake's database already is that record.

**Cost.** Status reads queued throughout. A leave landing during the requeue can leave an entry in the engine that intake has forgotten.

### Intake keeps its state in its own database

**Options.** Keep intake's records in memory, share matchmaking's database, or give intake a database of its own.

**Chosen.** Its own. Queued, matched and refused players live in intake's Postgres, so any number of intake copies serve any player and one dying hands its players to the rest without touching the engine. A player is in at most one state, so one `players` table carries an entry, a match and side, or a refusal, and a check constraint refuses a row with two. `IntakeStore` replaces `QueueRegistry`, `MatchBoard` and `RejectionBoard`, one transactional method per event, because a match must release an entry and record the match in one transaction.

**Cost.** Every request is a database round trip, and intake needs Postgres to start.

### A repeated join with the same members is sent again

**Options.** Refuse any join from someone already queued, or send the existing entry again when the members match.

**Chosen.** Send again, for the same member set only. A client that lost its reply retries, and the retry republishes the entry it already made, with its original id and queue time, so a copy that died between commit and publish cannot strand the player. Any other overlap is refused as before.

**Cost.** A retry and a fresh join are indistinguishable by design, so a player cannot restart their queue time by joining again.

### Concurrent writes are settled by one statement

**Options.** Read, decide in Java and write, or lock rows first, or make each check and its write one SQL statement.

**Chosen.** One statement. A join claims each member with `update ... where entry_id is null`, a solo's entry with `insert ... on conflict do nothing`, a result with `update matches ... where winner is null`, a rating change with `rating = rating + :delta`, and registration with `on conflict do nothing`. Postgres locks the row while it writes, so the second of two racers sees the first's result instead of a stale read. A party's members are claimed in id order, so two joins sharing members in opposite orders cannot deadlock.

**Cost.** Native SQL for every such write, outside JPA's entity tracking, so each carries `clearAutomatically` to keep loaded entities from going stale. Each race has a test of many rounds on separate connections, run outside the test transaction.

### A result is reported or tossed, and moves ratings by 100

**Options.** An Elo update from both teams' ratings, or a flat change, and a random winner or one reported by the caller.

**Chosen.** Flat, reported. `POST /matches/{id}/result` takes a winner from the caller, standing in for a game server, and tosses a coin without one. Every winner gains 100 and every loser loses 100, held to 1 to 5000. The result, the end time and all ten ratings commit together, and publishing `MatchEnded` waits for the commit so intake is never told of a result that rolled back.

**Cost.** Ratings measure wins, not strength against the opponent. A lost `MatchEnded` leaves players reading matched until they queue again, with ratings already moved.

### Players are created outside matchmaking

**Options.** Create a player at 2500 the first time they are seen, or require an account first.

**Chosen.** Require one. Accounts belong to a system this project stands in for, so a player exists before they queue, and a join naming anyone unknown is refused as `UNKNOWN_PLAYER`. `POST /players` creates one at 2500 under the caller's own id, and a seed of twenty is loaded for local runs only, through the `local` profile.

**Cost.** A refused party is not told which member was unknown. The seed is a repeatable migration outside the default folder, so Flyway is told a missing repeatable migration is expected.

### Tests run against a real Postgres

**Options.** Mock the repositories, use an in memory database, or start Postgres per test run.

**Chosen.** Real Postgres through Testcontainers, one container per test run shared by every class, with the real migrations. Native SQL, `on conflict`, check constraints, foreign keys and row locking are Postgres behaviour a mock or another database would not reproduce. Each test runs in a transaction rolled back at its end, and tests that must commit, the races, empty the tables when done.

**Cost.** Docker is needed to test either service, and a run starts a container. Testcontainers is pinned to 1.21.4, since the version Spring Boot chose cannot talk to Docker Engine 29.

### Spring Data JPA with Flyway

**Options.** Hand written SQL through JDBC, or Spring Data JPA, and schemas created by Hibernate or by versioned migrations.

**Chosen.** JPA for reads and plain rows, migrations with Flyway. Entities hold ids as plain columns rather than associations, so no query is ever loaded lazily behind the caller's back. Hibernate only validates the schema against the entities; Flyway owns it.

**Cost.** Two ways of writing to the database, repository methods and native statements, and a reader has to know which is which.

### Seats keep the rating and queue time at the match

**Options.** Keep only each player's current rating, or snapshot it on every seat.

**Chosen.** Snapshot. `player_matches` holds one row per player per match with side, rating before and queued at, because `players.rating` is overwritten by every result and queue time analytics need what each player had when they waited. Queue time is formed at minus queued at, so no separate table of queue events is kept. `matches.formed_at` is indexed for the last hour's waits, and `player_matches.player_id` for history, since the primary key leads with the match.

**Cost.** Leavers are never recorded, so every wait figure describes players who were matched.

## Two services over a queue

### Each module is its own CI job

**Options.** One job building everything, one job with a step per module, or a matrix running one job per module.

**Chosen.** A matrix. Each module reports its own check on a pull request, so a broken service cannot hide behind the others passing, and the jobs run independently, so one failure does not cancel the rest.

**Cost.** Setup is repeated per job, and `common` and `matchmaking-core` compile again inside every job that depends on them.

### A rejoin mid pass cancels the withdrawal

**Options.** Refuse the rejoin as a duplicate, give every solo join a fresh entry id, or let the rejoin cancel the pending withdrawal.

**Chosen.** Cancel. A solo's entry id is their own id, so a player who leaves and rejoins while their anchor is mid pass arrives with an id the engine still holds in flight. Refused, intake would read it as a redelivered join and ignore it, and the player would show as queued while held nowhere. Intake refuses a join from anyone already queued, and one queue keeps order, so an id both in flight and withdrawn can only be a rejoin, and one in flight but not withdrawn can only be a redelivery.

**Cost.** The player keeps their original queue time rather than the rejoin's, at most one pass of extra credit. A fresh id per solo join was rejected because `Player.id` has to stay the person, for ratings and for history.

### A leave that reaches an anchor mid pass is marked

**Options.** Let the leave find nothing, or record it for the pass to act on.

**Chosen.** Record it. A claimed anchor is in none of the index, the heap and the cooldown queue, so a leave arriving mid walk finds nothing, and every ending of the pass then keeps the player: the commit seats them, cooling requeues them, and the abnormal exit reinserts them. `MatchMaker` tracks anchors in flight from poll to settle, and a leave for one of them is marked withdrawn. Every verify checks the mark first and drops the anchor. Both sets only ever hold anchors currently in flight, so neither grows. Members other than the anchor need nothing new, since a leave takes them out of the index and the verify already catches them.

**Cost.** Two more sets to keep under the commit lock. No test can place a leave mid walk on demand, so the fix rests on a race test, 300 rounds of four runner threads against a leaving thread, which fails when the mark check is removed.

### Joins and leaves enter the engine under its lock

**Options.** Let the service take the commit lock around its own index and heap writes, or give `MatchMaker` methods that do.

**Chosen.** `enqueue` and `withdraw` on `MatchMaker`. The lock never leaves the engine, which keeps every matching decision there. A withdraw clears the index, the heap and the cooldown queue, since an entry left cooling would return as an anchor ten seconds after the player had gone.

**Cost.** Joins and leaves contend with passes for one lock. Each holds it briefly, and a pass releases it for the walk, so a waiting join gets in between a pass's sections without any priority.

### A lobby is published after the engine commits it

**Options.** Publish `EntryMatched` and then commit, commit and then publish, or write the match durably first and publish from that record.

**Chosen.** Commit, then publish. Publishing first would announce lobbies that the verify can still reject. A durable record needs a database, which the services do not have yet.

**Cost.** If the publish fails or matchmaking dies between the two, the lobby is lost: the engine no longer holds the ten, intake still does, and it refuses their rejoins. A broker outage is enough to cause it. The fix is an outbox in Postgres, written with the match and published from until marked sent.

**Amended.** A failed publish no longer loses the lobby: the match is deleted and its entries go back into the engine, and a matchmaking restart requeues everything intake holds, so the outbox was not built. What remains is a crash between saving the match and publishing it, which leaves a match that is never resulted.

### One runner thread, woken by a join or a second

**Options.** Run a pass on every join, spin a loop continuously, run on a fixed schedule, or loop and wait for a join or a timeout.

**Chosen.** A loop that runs passes until one comes back empty, then waits for a join or one second. Spinning burns a core on empty passes whenever ten players are too far apart to match. Waking on joins alone misses the case the widening window exists for: time passes, windows grow, a lobby becomes possible, and nothing arrives to notice. At the fastest point on the curve a window widens by about 3.5 rating points a second, so a second's wait misses little. No pass runs while fewer than ten players are queued, which `SkillIndex` now counts.

**Cost.** A lobby made possible by widening alone forms up to a second late. Nothing but the player count gates a pass: deciding whether any ten players can match is what the pass does, so a cheaper check would either turn away real lobbies or cost as much.

### One event per lobby

**Options.** One `EntryMatched` per entry, or one per lobby.

**Chosen.** One per lobby, carrying both teams as entry ids. Per entry, a crash after four of ten publishes leaves six entries that intake holds as queued and the engine no longer does, stuck for good. One message is delivered whole or not at all.

**Cost.** A lobby holds players while intake names entries, so matchmaking translates players back into entries through a map of its own, and intake expands entries back into players from its records.

### Ratings stay in matchmaking

**Options.** Carry each member's rating on the join event, or carry ids only and look ratings up where they live.

**Chosen.** Ids only. Matchmaking will own the rating update, so it owns the ratings, and nothing on the wire can disagree with them. Intake creates the entry id: a party's is fresh per join, and a solo's is their own id, so a player in a lobby is the person.

**Cost.** The party spread cap moved to matchmaking, the only service that can see ratings. A party over the cap is accepted by intake and refused afterwards, so the player learns of it through status rather than from the join.

**Amended.** Ratings now live in matchmaking's database, and a player with no row is refused as unknown instead of starting at 2500. `EntryAccepted` carries an entry's rating back to intake for the wait estimate; intake still never sends one.

### Intake changes its own records last

**Options.** On a failed publish, fail the request, or hold the event in memory and retry.

**Chosen.** Fail. A join records the entry and then publishes, removing the record and answering 503 if the broker refuses. A leave publishes first and forgets the entry only once the broker has it, since forgetting first would let the player rejoin while matchmaking still holds the old entry. Under both, intake changes its records only once matchmaking can know. A held event is lost if intake restarts after telling the player they queued, and its queue time would credit time spent waiting inside intake.

**Cost.** While the broker is down nobody can join or leave.

**Amended.** Intake's records are now rows in its own database, and the order is unchanged. A join committed but never published is sent again by a retry of the same join, or by the sweeper once 30 seconds pass unconfirmed.

### Intake refuses anyone already queued

**Options.** Leave duplicate detection to the engine, or track who is queued in intake.

**Chosen.** Intake. The engine refuses a repeated entry id, not a repeated person, so a double click, or a player queued solo and in a party at once, is only visible to intake. `QueueRegistry` checks and records under one lock, so two concurrent joins by the same player cannot both pass.

**Cost.** Joining a party while queued solo is refused rather than moving the player across. The registry is in memory while the queue is durable, so joins left in the queue across an intake restart reach the engine as entries intake no longer knows.

**Amended.** `QueueRegistry` is gone. The check is a conditional update on each member's row in intake's database, which holds across every intake copy where the lock held within one process. A join naming exactly the members of an existing entry now sends that entry again instead of being refused. Intake's records survive a restart, so queued joins no longer reach the engine as entries intake has forgotten.

### Events in common, as JSON, one queue per direction

**Options.** Define events in each service or once in `common`, serialize them as Java objects or as JSON, and use one queue per event type or one per direction.

**Chosen.** Once in `common`, so a field added on one side only is a compile error rather than a silent null. `common` depends on nothing, so events use plain JDK types. JSON, since RabbitMQ carries bytes, and JSON bytes are readable in its dashboard and not tied to Java. One queue per direction, with the event named in the message's type property, since a leave must never overtake the join for the same entry and separate queues would not keep that order.

**Cost.** The compile time guarantee holds per build. Deployed separately, an old intake can run beside a new matchmaking, so events can only change by adding fields a reader can ignore.

### A failing message is dropped, not requeued

**Options.** Requeue a message whose handler throws, route it to a dead letter queue, or drop it.

**Chosen.** Drop and log. Requeued, a message that always fails loops forever.

**Cost.** The message is gone and only the log records it. A dead letter queue that keeps failures for inspection is not built.

### Spring Boot for both services

**Options.** A minimal web library with the plain RabbitMQ client, or Spring Boot with Spring AMQP.

**Chosen.** Spring Boot. It is what most Java backends run on, and Spring AMQP takes care of connections, listener threads and message delivery that the plain client leaves to the caller. `RabbitTemplate` is safe across threads, so publishing needs no lock of its own.

**Cost.** Much of the request path happens by annotation and auto configuration, so how a request reaches a handler is harder to follow than in a framework used as a library. Startup and images are heavier, which the container stages will pay for.

## Parties and teams

### The race test queues parties as well as solos

**Options.** Keep the race test on its 300 solo players and test parties single threaded, or mix parties into the contended population.

**Chosen.** Mixed. A party is claimed as one entry, so the anchor claim and the verify should keep it whole, but that is an argument, and every race the engine has had looked fine as an argument until eight threads ran it. Half of 280 people queue in parties, sizes cycling two, three, four, five in a fixed order, each party followed by as many solos. Solos fill the gaps parties leave: a queue of only four stacks forms nothing, since each side is left one seat short, and a round where nothing forms never races.

The order is fixed rather than random so a failing round reproduces.

**Cost.** One shape of population, where a random one would wander into shapes nobody thought of. Two existing checks had to change units, since the engine claims entries and lobbies hold people: the nobody lost check now sums entry sizes, and the heap check looks up a seated player's entry first. Unchanged, the first fails on a correct run and the second passes on any run.

### A lobby is two sides of five

**Options.** Count ten seats into one list, as the walk did before parties, and split into teams afterwards, or fill two sides of five as the walk goes.

**Chosen.** Two sides during the walk. A party must sit on one team, and ten seats filled without regard to sides can be unsplittable: three parties of three and a solo fill every seat, and no subset of them adds up to five. Filling sides directly means every lobby that forms is already playable.

A candidate goes to the first side with room for all of it, team A before team B, and is passed over if neither has room. The check happens before seating and nothing seated is ever undone, so the walk stays one directional and never backtracks.

**Cost.** The walk can strand seats. A solo anchor and a four stack fill team A, a second four stack leaves team B one short, and if no solo remains in the window the pass fails holding nine usable players. The anchor cools and returns with a wider radius, so nothing breaks, but it is a second way for the greedy walk to miss a lobby that exists.

Measured, it is rare while solos are plentiful and dominant once they run out. 20k people, normal spread, eight workers, three seconds, three repeats: with half the people in parties, 2 passes stranded and nobody was left queued. With nine in ten in parties, 27318 passes stranded and 2000 people were never matched, because the parties left over had sizes that no combination makes into two fives. The queue in that run is closed, so no new solo ever arrives to finish a side. A live queue keeps receiving them, which makes this a worst case rather than a steady state.

### A party queues as one entry

**Options.** Queue each member individually and keep them together during selection, or queue the party as a single entry.

**Chosen.** One entry. A party with one shared queue time and one rating carries exactly what the index, the heap and the consent check consume, so it goes through all three unchanged. It is polled as one anchor, offered by the merge as one candidate, and checked for consent as one rating.

Queueing members individually breaks on recruitment. The walk offers candidates one at a time and has no idea two of them are friends, so it can seat two of a three stack and leave the third. A single entry makes that impossible, because the merge never offers half of one.

It is also cheaper. Every draw, consent check, verify and removal is paid once per entry, so a lobby of two five stacks costs about two of each where a lobby of solos costs ten. 20k people, normal spread, eight workers, fifteen repeats: 731 lobbies in 100ms with solos only, 1097 with half the people in parties, 1208 with nine in ten.

**Cost.** The engine cannot see individual members' ratings, so it cannot check the party's internal spread. That rule is enforced when the party is formed and taken on trust from then on.

### A sealed interface over two records

**Options.** A party as a subclass of player, a shared abstract class, or a sealed interface that both implement.

**Chosen.** `QueueEntry`, sealed over `Player` and `Party`. A record is final, so a party cannot extend a player, and a player is not a special case of a party anyway. The interface is small: id, rating, queue time, size and members. A player answers size one and a member list of itself, which is what lets `MatchMaker` treat both the same everywhere. Without that, every seat count and every commit would carry a branch on which kind of entry it holds.

**Cost.** A player now carries two methods that only mean something for parties, and code that needs people rather than entries has to unpack members explicitly.

### A party's rating is pulled toward its strongest member

**Options.** The mean of the members, the highest rated member, the midpoint of the extremes, or the mean shifted toward the highest.

**Chosen.** `mean + 0.5 * (max - mean)`, floored. The mean alone lets a strong player queue with weaker friends and play easier games. The highest member alone punishes every ordinary party that happens to have one stronger friend. The shift scales with the gap between the mean and the strongest member, so it is close to invisible on a tight party and large on an abusive one. A party of 1450, 1500 and 1550 moves by 25. Four players at 1000 with one at 3500 are rated 2500 rather than 1500. 0.25 barely moves either, and 0.75 starts charging ordinary parties.

The shift is computed in floating point even though, at exactly 0.5, integer division gives the same answer for every possible party. Relying on that would make the constant a coincidence rather than a setting.

**Cost.** Dilution. Because the base is the mean, adding more low rated friends pulls the rating back down, so one strong player's influence weakens as the party grows. The same 3500 player with one friend at 1000 is rated 2875, and with four is rated 2500, 375 less. The midpoint has no dilution and was not chosen.

### Parties are two to five, and their spread is capped

**Options.** Cap party size at the lobby, at the team, or not at all, and cap the rating gap between members or leave it free.

**Chosen.** Two to five, since a party has to fit on one team and a team is five. Ten would admit a party of seven that fits the lobby and fits no side. The gap between the highest and lowest member is capped at 2500, checked when the party is built.

**Cost.** The spread cap is a rule the engine cannot enforce, for the reason above. When parties are formed over the network, the check and the admission of a new member have to be one indivisible step, or two concurrent joins each pass the check alone and together produce a party wider than the cap.

**Reversed.** The cap is checked in `matchmaking-service` rather than where parties are formed. Ratings do not travel on the join event, so intake cannot see them. Matchmaking builds the party from a member list fixed in the event, so no concurrent join can widen it, and the check needs no lock.

### Membership is frozen while queued

**Options.** Let members join or leave a queued party in place, or require the party to leave the queue, change, and queue again.

**Chosen.** Frozen. The party's rating, spread and radius all come from its members, so a queued party that changed would be described wrongly by every structure holding it. Rebuilding means a new party with a new id and a new queue time. That also answers what happens to the accumulated wait: it resets, because the party that waited is not the party now queueing.

A member leaving, even by disconnecting, dequeues the whole party. It is not requeued automatically as a smaller party, since a three stack is a different thing from a four stack and should not be committed to a match it never asked for.

**Cost.** Waiting time is lost on any change, and the remaining players sit idle after a departure until one of them requeues.

### A party's id is random

**Options.** Derive the id from the members, so the same people always produce the same id, or generate a fresh one every time.

**Chosen.** Random. A derived id looks attractive because a membership change would produce a new id automatically. It breaks when membership does not change: the same three people queueing an hour apart get the same id, so a worker holding the old entry would verify against the new one, find it present, and commit a party with an hour old queue time and the wrong radius.

**Cost.** A party's id means nothing outside the engine, so whatever forms parties upstream needs its own handle on one and a way to associate the two.

**Amended.** Intake creates the id, fresh per join, and `Party.of` gained an overload taking it, so both services name a party by one id. The rule is unchanged: the same people queueing twice get two ids.

### The constructor verifies the stored rating

**Options.** Trust the caller to pass the right rating, hide construction behind a factory, or recompute and reject a mismatch.

**Chosen.** Recompute and reject. A record's canonical constructor is public, so a factory is a convention rather than a guarantee, and a party whose stored rating disagrees with its members would fail silently in every structure that files it by rating. `Party.of` computes the rating for the caller; the constructor checks it whoever calls.

**Cost.** Every party computes its rating twice when built through the factory.

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

**Cost.** The compensating action that the members deliberately avoid. An unsettled anchor is in neither structure, so any abnormal exit has to put them back, and a process that dies mid pass loses them until the queue redelivers. Measured throughput is unchanged, so this buys an invariant rather than speed: a queued entry is in exactly one place at any moment.

**Amended.** The counter for passes that lost their anchor, and the test asserting it stayed at zero, were removed. The verify never checks the anchor, since the claim has already taken them out of the index, so the counter could not move and the test could not fail.

**Amended.** Being in neither structure also hid a claimed anchor from a leave. `MatchMaker` now records anchors in flight, and a leave for one is marked for its pass to act on.

### A retry budget of the seats standing

**Options.** Abandon the pass when a member is taken, retry without limit, or retry a bounded number of times.

**Chosen.** Bounded, and the bound is the number of seats still standing at the first failed verify, so a nearly complete lobby is worth more persistence than a bare one. It is set once. Recomputing it from a later, fuller selection lets it grow, and the loop stops terminating under exactly the contention it exists for.

Retrying is for contention, where another worker committed and the index has changed. A refill that finds nobody is starvation, where nothing has changed and asking again microseconds later returns the same answer, so that cools instead.

**Cost.** A selection that starts at three seats and grows to nine keeps the budget of three, so a lobby that became valuable mid pass is not credited for it. Termination is worth more than the credit.

**Amended.** The budget was computed as members seated minus members lost, which was the seat count only while every member was one person. With parties it counts seats explicitly, each entry contributing its size, so losing a five stack costs a pass five seats rather than one.

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

**Amended.** Still longest waiting first, but a candidate is now also skipped when neither team has room for all of it. The walk previously stopped at ten seats in one list; it now fills two teams of five.

### One attempt per call

**Options.** Loop internally until something forms, or return after one attempt.

**Chosen.** One attempt. Every call either seats ten players or cools one anchor, so both outcomes shrink the heap and a caller loop terminates without tracking what it tried. Looping internally would hold the commit lock across an unbounded number of attempts.

**Cost.** The matcher alone does nothing. Cadence belongs to the caller.

**Amended.** Still one anchor per call, but no longer one attempt: a pass that loses a member to another worker refills and verifies again, within a budget. The termination argument survives, since the budget is set once and decremented, and every call still shrinks the heap.

### A fixed cooldown for failed anchors

**Options.** Reinsert immediately, a fixed period, or a period growing with each failure.

**Chosen.** Fixed at 10 seconds. Immediate reinsertion fails outright, since the heap orders by queue time and reinsertion does not change it, so the same player returns on the very next call. Growing cooldowns point the wrong way: the repeat failer is the player furthest from the rating mass, precisely who the heap protects, and the moment they become matchable is uncorrelated with their failure count. Queue time is untouched while they sit out, so they return to the front with a wider radius.

**Cost.** 10 seconds is a constant with nothing behind it, and no failure count is stored, so a player failing repeatedly goes unnoticed.

**Amended.** The period is a constructor argument now, defaulting to the same ten seconds. It is policy rather than physics, and a cooldown longer than a measurement window makes the measurement about the cooldown. Cooling also records its cause, since an anchor with no lobby available and an anchor that lost too many races are different facts about the queue wearing the same treatment.

**Amended.** A third cause, stranded, split out of starvation. A short pass counts as stranded when the walk turned away a candidate who would have consented but fit neither team, and as starvation otherwise. Without the split, a lobby lost to party sizes and a window with nobody in range read as the same number. A candidate turned away who would also have failed consent does not count, since room was never what kept them out.

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

**Amended.** Since the index became skip lists, mutating it mid draw no longer throws. The hazard is now that an entry drawn may already have left, which the commit verifies.

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

**Reversed, to `ConcurrentSkipListMap`.** Matching now selects candidates without holding the lock, which means iterating a window while another worker commits into it. A `TreeMap` iterator is fail fast, so workers were surviving `ConcurrentModificationException` mid pass, and fail fast is documented as best effort, so the silent corruption behind it could not be ruled out either. Copying the window instead would destroy the laziness the whole index exists for, and taking the lock during selection would serialise the expensive part of a pass.

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

**Reversed, to `ConcurrentSkipListSet` ordered by wait time.** The same concurrent iteration problem as the map above, and a bucket iterator is live for far longer than the map's, since it stays open for the whole candidate walk.

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

**Amended.** Buckets hold queue entries rather than players, and the method was renamed from `playersInRange` to `entriesInRange` to say so. `playerCount` became `entryCount` for the same reason: a queued party is one entry, so the count no longer equals the number of people waiting.

### Range queries are lazy

**Options.** A materialised list, an iterator, or a stream of live bucket views.

**Chosen.** A stream. This is the decision the rest of the index hangs on. An anchor five minutes into the queue accepts a radius of 899, so the window spans roughly 1800 ratings and can hold thousands of queued players. A lobby seats ten. Returning a list means building every one of those to hand back ten, which throws away the entire advantage of indexing by rating in the first place.

A stream rather than an iterator because it composes: `flatMap`, `limit` and `takeWhile` come free, which is how tests flatten buckets back to players in one line and how the matcher stops early without a loop tracking its own count.

**Cost.** The buckets handed out are live views, not copies. Since both levels became skip lists the draw no longer throws when another thread mutates the index, but it is still weakly consistent: a player drawn from it may already have left. The commit verifies for exactly that reason.

### No side index by player id

**Options.** A map from id to player beside the tree, or the rating keyed index alone.

**Chosen.** No side index. Uniqueness becomes a rule upstream: a queued player must leave before queueing again.

**Cost.** `SkillIndex` is not authoritative about its own contents. The same id inserted at two different ratings lands in two buckets and both inserts succeed, structurally the same defect that ended the AVL attempt. An upstream check is a policy, not a guarantee, and two threads can interleave through it. Due for revisit when the worker pool lands.

**Reversed.** A map from id to player now sits beside the ordered map and is the authority on what is queued. The revisit was forced by the concurrency fix, which has to ask whether an id is still queued at all, and a rating keyed index can only answer whether a player is queued at a given rating.

Three consequences. Insert refuses a duplicate id whatever rating a second join carries, which closes the orphan entry above. Remove takes the id as the address and ignores the rating on the argument, so a caller holding a player whose rating has since changed still removes the right entry. The player count is the map's size, so it cannot drift from the contents.

**Cost.** Two structures that must stay in step, the same discipline the fairness heap already carries with its position map, and it fails silently when broken. Removing with a stale rating used to return false; it now succeeds, which reads like losing a check but is not, since nothing was reading that false.

## The domain model

### Records and immutability

**Options.** Mutable objects updated in place, or immutable records replaced on change.

**Chosen.** Records. Several structures, and soon several threads, hold the same player. A player whose fields change underneath a reader is a data race once workers run concurrently, and mutating a rating in place would strand the player in the wrong bucket.

**Cost.** A change means constructing a new player and re inserting them, so the structures are updated rather than the object.

**Amended.** `Player` now implements `QueueEntry`, and every structure that held players holds entries instead: `SkillIndex`, `FairnessHeap`, `WaitTimeMerge`, `Overlap` and `MatchMaker`'s cooldown queue. The wait time comparator moved from `Player.BY_WAIT_TIME` to `QueueEntry.BY_WAIT_TIME`. Behaviour for solo players is unchanged, which the existing tests showed by passing across the retyping.

`Lobby` changed shape too. It was one list of ten members; it is now two lists, `teamA` and `teamB`, with `members()` kept as a method returning both, team A first. The anchor is first on team A, so `anchor()` still answers the same player.

### Identity is the id alone

**Options.** The record's generated equality over all three components, or an override on id.

**Chosen.** The override. A player reconstructed on the far side of a message queue will not carry a bit identical queue time, and removal must still find them.

**Cost.** Equality disagrees with the record's own components, which surprises a reader expecting generated behaviour. Two players with the same id and different ratings compare equal despite occupying different buckets, which is the hole the missing side index leaves open.

**Amended.** `Party` overrides equality the same way, so identity by id alone is now the contract of `QueueEntry` rather than a property of `Player` only.

## Repository and build

### Four modules rather than one

**Options.** One module and split later, or four from the start.

**Chosen.** Four. `matchmaking-core` carries no framework or network dependency, enforced by the module boundary rather than by discipline. The dependency arrow points inward and nothing depends on a service.

**Cost.** Four build files to keep in step, and a tree that looks disproportionate to what it holds.

### CI from the first commit

**Options.** Build the pipeline at the end, or before any real logic.

**Chosen.** First, against a placeholder test. The branch to pull request to green check loop becomes the working habit, and a review process bolted on afterwards is visible in the commit history.

**Cost.** The pipeline proves very little for the first few commits.

**Amended.** The single build job became one job per module once the services had code.

### Feature branches and squash merges

**Options.** Direct commits with occasional pull requests, or branch protection with no exceptions.

**Chosen.** Branch protection, no direct pushes, CI green before merge, squash merge so `main` reads as one commit per unit of work.

**Cost.** Process overhead disproportionate to a solo project, and a standing temptation to open a pull request for its own sake. Trivial changes are batched instead.
