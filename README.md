<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/logo-dark.svg">
    <img src="docs/logo.svg" alt="Thebes: a pen sketch of the pyramids of Giza above the winding Nile" width="400">
  </picture>
</p>

Thebes was one of the great cities of ancient Egypt, on the banks of the Nile. This Thebes is a small [lambda architecture](https://en.wikipedia.org/wiki/Lambda_architecture) for realtime data, written in about 1,200 lines of heavily commented Clojure, plus an example app and a demo. You can read it in an afternoon and run it with one command.

Events arrive over HTTP, flow through a queue, and land in an append-only master dataset. Two layers compute the same views from that data: a **batch layer** that recomputes everything from scratch, in parallel, and a **speed layer** that folds each new event in as it arrives. The serving layer merges the two layers, so every answer is complete and up to date to the latest event. The demo checks this by comparing the merged answer against a full recompute.

```
clojure -M:demo
```

```
  THEBES · lambda architecture demo                       nile app · memory transport

  Season  Aug 23 23:00  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  day 30 of 30
  Sensors 24/24 reporting

  STATION         READINGS   LEVEL    PEAK  DAILY PEAK                      STATUS
  Khartoum           2,880   3.81m  11.39m  ▃▄▆▇██▇▆▄▃▂▂▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁  normal
  Atbara             2,783   3.59m  10.25m  ▁▂▃▄▆▇██▇▆▄▃▂▂▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁  normal
  Aswan              2,880   2.91m   8.34m  ▁▁▁▁▁▁▁▁▂▂▃▅▆███▇▆▄▃▂▂▁▁▁▁▁▁▁▁  normal
  Luxor (Thebes)     2,880   2.85m   7.84m  ▁▁▁▁▁▁▁▁▁▂▂▃▄▆▇██▇▆▄▃▂▂▁▁▁▁▁▁▁  normal
  Asyut              2,880   2.42m   7.07m  ▁▁▁▁▁▁▁▁▁▁▁▁▂▃▄▆▇██▇▆▅▃▂▂▁▁▁▁▁  normal
  Cairo              2,880   2.07m   6.42m  ▁▁▂▁▁▁▂▁▁▁▂▁▁▂▂▂▃▅▆▇██▇▅▄▃▂▂▂▁  normal

  Layers  ████████████████████████████████████████████
          █ batch 13,751 (run 7, 45 ms)   █ speed 3,432   ░ in flight 0

  Flood alerts · stateful stream in the speed layer · 12 so far
    Aug 10 05:02  ▲ Asyut           rose above flood stage  5.92m > 5.90m
    Aug 10 19:04  ▼ Aswan           receded                 6.49m
    Aug 12 02:01  ▼ Luxor (Thebes)  receded                 6.09m
    Aug 12 20:01  ▲ Cairo           rose above flood stage  5.35m > 5.20m
    Aug 14 07:04  ▼ Asyut           receded                 5.49m
    Aug 16 15:04  ▼ Cairo           receded                 4.79m

  ✓ 17,183 readings ingested over HTTP in 20.1s (854/s)
  ✓ master dataset: /tmp/thebes-demo-…/events.jsonl (17,183 events, 2.7 MB)
  ✓ batch layer ran 8 times; full recompute of 17,183 events took 42 ms
  ✓ 12 flood alerts from the speed layer's stateful stream
  ✓ realtime view (batch ⊕ speed: 13,751 + 3,432 events) == full batch recompute
```

The demo simulates a flood season on the Nile. Six gauge stations, each a modern [nilometer](https://en.wikipedia.org/wiki/Nilometer), report water levels every simulated hour while a flood wave travels downstream from Khartoum to Cairo. The readings go to a live Thebes node over HTTP. The dashboard is drawn from the node's query API as the batch and speed layers trade places, and you can watch the crest move down the river, station by station.

## Quick start

You need Java 17+ and the [Clojure CLI](https://clojure.org/guides/install_clojure).

```sh
clojure -M:demo                 # the guided demo (about 20 seconds)
clojure -M:demo --help          # season length, speed, seed, transport…
clojure -M:run                  # run a node on http://localhost:8080
clojure -M:test                 # run the tests
```

With a node running:

```sh
curl -X POST localhost:8080/api/v1/events -H 'Content-Type: application/json' \
  -d '{"station": "luxor", "sensor": "luxor-1", "ts": 1785000000000, "level": 7.1}'

curl localhost:8080/api/v1/views/stations?station=luxor
curl -N localhost:8080/api/v1/streams/flood-alerts/sse     # live alerts
```

## How it works

```
             ┌──────────────┐  transport  ┌─────────────────────────────────────────────┐
  POST ─────▶│ ingest       │────────────▶│ processor                                   │
  events     │ validate,    │  memory or  │                                             │
             │ publish      │  RabbitMQ   │  log (master dataset, append-only JSONL)    │
             └──────────────┘             │   │                                         │
                                          │   ├─▶ speed layer: core.async topology      │
                                          │   │     prepare ─┬─▶ views (per epoch)      │
                                          │   │              └─▶ streams (alerts)       │
                                          │   │                                         │
                                          │   └─▶ batch layer: parallel r/fold over     │
                                          │         the whole log, every N seconds      │
                                          │                                             │
  GET views ◀──────────────────────────── │  serving: batch views ⊕ live speed epochs   │
                                          └─────────────────────────────────────────────┘
```

**Views are monoids.** A view groups events and aggregates each group with an aggregation from [`thebes.agg`](src/thebes/agg.clj): `count`, `sum`, `min`, `max`, `mean`, `stats` (mean and standard deviation, merged with Chan's parallel algorithm), `latest`, and `distinct-approx` (a HyperLogLog sketch). There are also two combinators, `filtered` and `multi`. Each aggregation has an identity and an associative, commutative merge. That one property does all the work. The batch layer can split the log into chunks, fold them on every core and merge the results. The serving layer can then merge a batch view with the speed layer's partial views and get exactly the answer a single sequential pass would give. Property tests check these laws for every aggregation.

**Epochs hand work from the speed layer to the batch layer.** Each event gets an offset when it is appended to the log. A batch run starts by taking the current log size as its watermark, W. While holding the log's append lock, it also opens a new speed-layer epoch at W. Events below W keep landing in the old epoch. Events at or past W land in the new one. When the run finishes, its views replace the old batch views and the epochs below W are dropped, in a single atomic swap. Sometimes an event reaches the speed layer after a batch has already covered it. Its offset is below the watermark, so it is ignored. Every event is counted exactly once, at every moment. [`thebes.lambda`](src/thebes/lambda.clj) holds all of this as pure functions, and a property test checks the invariant against random interleavings of appends, processing and batch runs.

**The speed layer is a tiny Storm.** [`thebes.topology`](src/thebes/topology.clj) builds a dataflow graph from data. Each node is a transducer or a sink, wired together with core.async. Stateless nodes can run in parallel with order preserved. Single-threaded nodes keep their transducer's state, so streams like the flood detector, which uses hysteresis per station, are ordinary stateful transducers. Back-pressure flows from slow sinks all the way back to the HTTP handlers.

**The log is the source of truth.** Views can always be thrown away and rebuilt. Restart a node and it recomputes everything from `events.jsonl` before serving. The log is plain JSON lines, so you can grep it, `jq` it, or load it into something else.

## Writing your own app

An app is a map. This is the whole of the Nile example's definition, with the view bodies shortened. The full version is in [`thebes.examples.nile`](src/thebes/examples/nile.clj).

```clojure
(def app
  {:name     "nile"
   :validate validate                  ; event -> error message or nil
   :prepare  prepare                   ; event -> event, shared by both layers
   :views    [{:name     :stations
               :group-by {:station :station}
               :agg      (agg/multi {:readings (agg/count)
                                     :level    (agg/stats :level)
                                     :current  (agg/latest :level :ts)
                                     :sensors  (agg/distinct-approx :sensor)})}
              {:name     :daily
               :group-by {:station :station
                          :day     (view/time-bucket :ts :day)}
               :agg      (agg/multi {:peak (agg/max :level)})}]
   :streams  {:flood-alerts (flood-detector)}})   ; transducers over prepared events
```

Run it with `clojure -M:run --app my.namespace/app`. Put derived fields in `:prepare`, never in only one layer. Because both layers share that one function, the two can't disagree.

## Running distributed with RabbitMQ

The ingest and processor roles from the original 2013 design, `thebes-api` and `thebes-processor`, can run as separate processes. You can run as many stateless ingest nodes as you like, all in front of one processor:

```sh
docker compose up -d rabbitmq
clojure -M:run processor --transport rabbitmq --port 8081
clojure -M:run ingest    --transport rabbitmq --port 8080
```

Set `THEBES_RABBITMQ_URI` (or pass `--rabbitmq-uri`) to point at another broker. The demo runs over RabbitMQ too: `clojure -M:demo --transport rabbitmq`. Delivery is at least once. Ingest returns `202` only after the broker confirms the messages, the processor acknowledges each one only after it has been written to the master dataset, and a failed message is requeued.

## HTTP API

| Method | Path                             | Role      | Description                                          |
| ------ | -------------------------------- | --------- | ---------------------------------------------------- |
| POST   | `/api/v1/events`                 | ingest    | One event or an array; all-or-nothing validation     |
| GET    | `/api/v1/views`                  | processor | List views                                           |
| GET    | `/api/v1/views/:name`            | processor | Rows merged across layers; filter with `?part=value` |
| GET    | `/api/v1/streams/:name`          | processor | Recent stream items; poll with `?since=seq`          |
| GET    | `/api/v1/streams/:name/sse`      | processor | Server-Sent Events                                   |
| POST   | `/api/v1/batch`                  | processor | Run the batch layer now                              |
| GET    | `/api/v1/status`                 | processor | Log size, layer split, topology counters             |
| GET    | `/health`                        | any       | Liveness                                             |

## Project layout

```
src/thebes/
  agg.clj            mergeable aggregations (monoids), including HyperLogLog
  view.clj           grouped views; sequential and parallel (r/fold) compute
  lambda.clj         serving state: batch views + speed epochs, as pure functions
  topology.clj       core.async dataflow graphs (spouts and bolts, in-process)
  log.clj            the master dataset
  processor.clj      wires log, batch layer, speed layer and serving together
  ingest.clj         validation and publishing
  transport.clj      transport protocol; memory/ and rabbitmq/ implementations
  stream.clj         realtime output streams with SSE fan-out
  http.clj           routes and handlers (http-kit)
  system.clj         start/stop a node in any role
  main.clj           CLI
  demo.clj           the guided demo and terminal dashboard
  examples/          the Nile app and its flood simulation
```

## Limits

Thebes is a proof of concept. It is meant for learning from, and isn't hardened for production:

- There is one processor, and the master dataset lives on its local disk. A production system would use a distributed log (Kafka, object storage) and a distributed batch engine.
- The batch layer reads the whole log into memory on each run. That's fine for millions of small events, but not for billions.
- Speed-layer streams are ephemeral, so their state resets when a node restarts. Views are always rebuilt.
- Delivery over RabbitMQ is at least once. If a processor crashes between writing an event and acknowledging it, the redelivered event is appended a second time. Deduplicating by event `id` would fix that.
- An event that the app's `:prepare` or a view can't handle is skipped, by both layers alike. Skipped events aren't reported anywhere yet; a dead-letter stream would be the next step.

## History

The first version of Thebes, from 2013, sketched this design on Storm, Cascalog and RabbitMQ but never got its halves connected. In 2026 it was rewritten in modern Clojure (deps.edn, core.async, reducers, http-kit) and finished, keeping the original idea and the ingest/processor split. The old code is still in the git history.

## License

Copyright © 2013–2026 Chet Mancini

Distributed under the Eclipse Public License 1.0. See [LICENSE](LICENSE).
