# VideoPlayer - COMP3011 Assignment 2

For more information on this code and how to get started, see https://learn.adelaide.edu.au/courses/30747/assignments/94505

## Concurrency design

Playback controls and video decoding run on one background thread.
The JavaFX thread handles interface updates. Effects are divided into
consecutive groups, with one worker thread for each group.

### Frame ownership and effect order

Decoded frames are copied because the decoder may reuse its buffers.
Bounded queues pass these copies between stages. Only the stage
currently holding a frame may modify it, and each effect object belongs
to one stage. This preserves the command-line effect order.

### Seeking and shutdown

Each frame task contains a playback ID. Seeking changes the current ID,
so results from the previous position are discarded and released.

Shutdown interrupts the stage workers, waits for them to finish, and
releases remaining frames. This cleanup runs on the playback worker
rather than the JavaFX thread.

## AI assistance

GPT6.1 assisted with explaining concurrency concepts, suggesting code
changes, and debugging.
