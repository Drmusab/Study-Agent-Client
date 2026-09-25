#!/usr/bin/env python3
"""
GATE 11 — PC review-commit idempotency tests.

Run with ``python3 -m pytest server/test_review_commit_store.py`` or stdlib only:
``python3 server/test_review_commit_store.py``.

Each test counts *effects* in a simulated Anki scheduler, which is the quantity that matters:
responses can be duplicated or lost, scheduler mutations must not be.
"""

import json
import os
import random
import shutil
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from review_commit_store import (  # noqa: E402
    OUTCOME_APPLIED,
    OUTCOME_CONFLICT,
    OUTCOME_DUPLICATE,
    OUTCOME_REJECTED,
    OUTCOME_STORE_FAILED,
    OUTCOME_UNKNOWN,
    STATE_INTENT,
    NotAppliedError,
    ReviewCommitProcessor,
    ReviewCommitTable,
)


class SimulatedCrash(BaseException):
    """Process death: not an Exception, so nothing in the processor may catch and continue."""


class FakeAnki:
    def __init__(self):
        self.effects = {}
        self.crash_after_apply = False
        self.crash_before_apply = False
        self.refuse_next = None
        self.raise_after_apply = False

    def apply(self, request):
        if self.crash_before_apply:
            raise SimulatedCrash("before apply")
        if self.refuse_next:
            category, self.refuse_next = self.refuse_next, None
            raise NotAppliedError(category)
        key = request["review_commit_id"]
        self.effects[key] = self.effects.get(key, 0) + 1
        if self.crash_after_apply:
            raise SimulatedCrash("after apply, before result persisted")
        if self.raise_after_apply:
            raise RuntimeError("connection to Anki dropped after the call")
        return {"next_interval": "1 day", "effect": self.effects[key]}


def request(commit_id="s1:t1", rating="good", card="c1", turn="t1", session="s1"):
    return {"review_commit_id": commit_id, "session_id": session, "review_turn_id": turn,
            "card_id": card, "rating": rating}


class ReviewCommitStoreTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="gate11-pc-")
        self.path = os.path.join(self.dir, "commits.json")
        self.anki = FakeAnki()
        self.processor = self.new_process()

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def new_process(self):
        """Models a PC agent restart: only the file survives."""
        return ReviewCommitProcessor(ReviewCommitTable(self.path), self.anki.apply)

    # -- basic contract --------------------------------------------------------------------------

    def test_first_commit_applies_exactly_one_effect(self):
        result = self.processor.commit(request())
        self.assertEqual(OUTCOME_APPLIED, result.outcome)
        self.assertTrue(result.effect_applied_now)
        self.assertEqual({"s1:t1": 1}, self.anki.effects)

    def test_same_id_same_payload_returns_stored_result_without_effect(self):
        first = self.processor.commit(request())
        second = self.processor.commit(request())
        self.assertEqual(OUTCOME_DUPLICATE, second.outcome)
        self.assertFalse(second.effect_applied_now)
        self.assertEqual(first.result, second.result)
        self.assertEqual(1, self.anki.effects["s1:t1"])

    def test_same_id_different_payload_is_conflict_without_effect(self):
        self.processor.commit(request(rating="good"))
        for changed in (request(rating="easy"), request(card="c2"), request(turn="t9"), request(session="s9")):
            self.assertEqual(OUTCOME_CONFLICT, self.processor.commit(changed).outcome)
        self.assertEqual(1, self.anki.effects["s1:t1"])

    def test_conflict_also_applies_to_an_unresolved_intent(self):
        self.anki.crash_after_apply = True
        with self.assertRaises(SimulatedCrash):
            self.processor.commit(request())
        self.anki.crash_after_apply = False
        self.assertEqual(OUTCOME_CONFLICT, self.new_process().commit(request(rating="again")).outcome)

    def test_invalid_requests_never_touch_anki(self):
        for bad in ({}, request(commit_id=""), request(rating="perfect"), request(card="")):
            self.assertEqual(OUTCOME_REJECTED, self.processor.commit(bad).outcome)
        self.assertEqual({}, self.anki.effects)
        self.assertFalse(os.path.exists(self.path))

    # -- durability and restart ------------------------------------------------------------------

    def test_duplicate_after_restart_returns_stored_result(self):
        first = self.processor.commit(request())
        replay = self.new_process().commit(request())
        self.assertEqual(OUTCOME_DUPLICATE, replay.outcome)
        self.assertEqual(first.result, replay.result)
        self.assertEqual(1, self.anki.effects["s1:t1"])

    def test_crash_between_intent_and_apply_is_unknown_and_never_applied(self):
        self.anki.crash_before_apply = True
        with self.assertRaises(SimulatedCrash):
            self.processor.commit(request())
        self.anki.crash_before_apply = False
        restarted = self.new_process()
        self.assertEqual(OUTCOME_UNKNOWN, restarted.commit(request()).outcome)
        self.assertEqual("UNKNOWN", restarted.status("s1:t1"))
        self.assertEqual({}, self.anki.effects)  # conservative: not applied, and not auto-applied

    def test_crash_after_apply_before_result_persisted_never_applies_twice(self):
        self.anki.crash_after_apply = True
        with self.assertRaises(SimulatedCrash):
            self.processor.commit(request())
        self.anki.crash_after_apply = False
        restarted = self.new_process()
        for _ in range(3):
            self.assertEqual(OUTCOME_UNKNOWN, restarted.commit(request()).outcome)
        self.assertEqual(1, self.anki.effects["s1:t1"])

    def test_exception_after_entering_apply_is_unknown(self):
        self.anki.raise_after_apply = True
        self.assertEqual(OUTCOME_UNKNOWN, self.processor.commit(request()).outcome)
        self.anki.raise_after_apply = False
        self.assertEqual(OUTCOME_UNKNOWN, self.processor.commit(request()).outcome)
        self.assertEqual(1, self.anki.effects["s1:t1"])

    def test_intent_persist_failure_leaves_anki_untouched_and_retry_is_safe(self):
        self.processor.table.fail_next_writes = 1
        self.assertEqual(OUTCOME_STORE_FAILED, self.processor.commit(request()).outcome)
        self.assertEqual({}, self.anki.effects)
        self.assertEqual(OUTCOME_APPLIED, self.processor.commit(request()).outcome)
        self.assertEqual(1, self.anki.effects["s1:t1"])

    def test_result_persist_failure_reports_applied_but_replay_is_unknown(self):
        table = self.processor.table
        original_put = table.put
        calls = []

        def put(commit_id, record):
            calls.append(record["state"])
            if record["state"] == "APPLIED":
                raise OSError("disk full")
            original_put(commit_id, record)

        table.put = put
        result = self.processor.commit(request())
        self.assertEqual(OUTCOME_APPLIED, result.outcome)
        self.assertEqual("result_not_durable", result.category)
        self.assertEqual([STATE_INTENT, "APPLIED"], calls)
        self.assertEqual(OUTCOME_UNKNOWN, self.new_process().commit(request()).outcome)
        self.assertEqual(1, self.anki.effects["s1:t1"])

    def test_proven_not_applied_may_be_retried_with_the_same_id(self):
        self.anki.refuse_next = "card_not_due"
        self.assertEqual(OUTCOME_REJECTED, self.processor.commit(request()).outcome)
        self.assertEqual("NOT_APPLIED", self.new_process().status("s1:t1"))
        self.assertEqual(OUTCOME_APPLIED, self.new_process().commit(request()).outcome)
        self.assertEqual(1, self.anki.effects["s1:t1"])

    def test_persistence_order_is_intent_then_apply_then_result(self):
        order = []
        table = self.processor.table
        original_put = table.put
        table.put = lambda cid, rec: (order.append("persist:" + rec["state"]), original_put(cid, rec))
        self.processor.apply_fn = lambda req: (order.append("apply"), {"next_interval": "1 day"})[1]
        self.processor.commit(request())
        self.assertEqual(["persist:INTENT", "apply", "persist:APPLIED"], order)

    def test_store_loss_weakens_the_guarantee(self):
        """§436: deleting the table forgets the id, so a replay applies AGAIN. Documented limit."""
        self.processor.commit(request())
        os.remove(self.path)
        self.assertEqual(OUTCOME_APPLIED, self.new_process().commit(request()).outcome)
        self.assertEqual(2, self.anki.effects["s1:t1"])

    def test_memory_only_table_is_not_durable(self):
        self.assertFalse(ReviewCommitTable(None).durable)
        self.assertTrue(ReviewCommitTable(self.path).durable)

    def test_unknown_schema_fails_loudly(self):
        with open(self.path, "w") as handle:
            json.dump({"schema_version": 99, "records": {}}, handle)
        with self.assertRaises(ValueError):
            ReviewCommitTable(self.path)

    def test_table_stores_no_secrets_or_card_content(self):
        req = dict(request(), auth_token="secret-token", user_text="my private answer",
                   question_html="<b>Q</b>")
        self.processor.commit(req)
        raw = open(self.path).read()
        for forbidden in ("secret-token", "my private answer", "<b>Q</b>", "auth_token", "user_text"):
            self.assertNotIn(forbidden, raw)

    # -- concurrency and chaos -------------------------------------------------------------------

    def test_concurrent_duplicate_deliveries_produce_one_effect(self):
        barrier = threading.Barrier(8)
        outcomes = []

        def worker():
            barrier.wait()
            outcomes.append(self.processor.commit(request()).outcome)

        threads = [threading.Thread(target=worker) for _ in range(8)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        self.assertEqual(1, self.anki.effects["s1:t1"])
        self.assertEqual(1, outcomes.count(OUTCOME_APPLIED))
        self.assertEqual(7, outcomes.count(OUTCOME_DUPLICATE))

    def test_seeded_chaos_never_applies_an_id_twice(self):
        for seed in range(25):
            self._chaos_run(seed)

    def _chaos_run(self, seed):
        rnd = random.Random(seed)
        path = os.path.join(self.dir, f"chaos-{seed}.json")
        anki = FakeAnki()
        table = ReviewCommitTable(path)
        processor = ReviewCommitProcessor(table, anki.apply)
        acknowledged = {}
        ids = [f"s{seed}:t{n}" for n in range(6)]
        for step in range(120):
            commit_id = rnd.choice(ids)
            action = rnd.random()
            anki.crash_before_apply = action < 0.05
            anki.crash_after_apply = 0.05 <= action < 0.10
            anki.raise_after_apply = 0.10 <= action < 0.13
            if 0.13 <= action < 0.16:
                processor.table.fail_next_writes = 1
            try:
                result = processor.commit(request(commit_id=commit_id))
            except SimulatedCrash:
                processor = ReviewCommitProcessor(ReviewCommitTable(path), anki.apply)
                continue
            finally:
                anki.crash_before_apply = anki.crash_after_apply = anki.raise_after_apply = False
                processor.table.fail_next_writes = 0
            if result.outcome in (OUTCOME_APPLIED, OUTCOME_DUPLICATE):
                previous = acknowledged.setdefault(commit_id, result.result)
                self.assertEqual(previous, result.result, f"seed={seed} step={step}")
            if rnd.random() < 0.1:
                processor = ReviewCommitProcessor(ReviewCommitTable(path), anki.apply)
        for commit_id, count in anki.effects.items():
            self.assertLessEqual(count, 1, f"seed={seed} id={commit_id} applied {count}x")


class MockAgentAdvertisementTest(unittest.TestCase):
    """The mock only advertises review_commit_idempotency when the table is durable."""

    def test_capability_follows_durability(self):
        try:
            import mock_pc_agent  # noqa: F401  (needs the websockets package)
        except (ImportError, SystemExit, Exception) as exc:  # pragma: no cover - env dependent
            self.skipTest(f"mock_pc_agent not importable: {exc}")
        import mock_pc_agent as agent
        memory = agent.build_commit_processor(None)
        self.assertFalse(memory.table.durable)
        directory = tempfile.mkdtemp(prefix="gate11-adv-")
        try:
            durable = agent.build_commit_processor(os.path.join(directory, "c.json"))
            self.assertTrue(durable.table.durable)
        finally:
            shutil.rmtree(directory, ignore_errors=True)
        self.assertNotIn(agent.REVIEW_COMMIT_IDEMPOTENCY, agent.FULL_CAPABILITIES)


class MockAgentEndToEndTest(unittest.TestCase):
    """Real mock_pc_agent process over WebSocket; restart keeps only the --commit-store file."""

    PORT = 18765

    def setUp(self):
        try:
            import websockets  # noqa: F401
        except ImportError:  # pragma: no cover - env dependent
            self.skipTest("websockets not installed")
        self.dir = tempfile.mkdtemp(prefix="gate11-e2e-")
        self.store = os.path.join(self.dir, "commits.json")
        self.proc = None

    def tearDown(self):
        self._stop()
        shutil.rmtree(self.dir, ignore_errors=True)

    def _start(self):
        import subprocess
        import socket
        import time
        here = os.path.dirname(os.path.abspath(__file__))
        self.proc = subprocess.Popen(
            [sys.executable, os.path.join(here, "mock_pc_agent.py"), "--port", str(self.PORT),
             "--commit-store", self.store, "--no-tts"],
            cwd=here, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        deadline = time.time() + 15
        while time.time() < deadline:
            try:
                socket.create_connection(("127.0.0.1", self.PORT), timeout=0.2).close()
                return
            except OSError:
                time.sleep(0.1)
        self.fail("mock agent did not start")

    def _stop(self):
        if self.proc:
            self.proc.terminate()
            self.proc.wait(timeout=10)
            self.proc = None

    def _exchange(self, frames):
        import asyncio
        import websockets

        async def run():
            replies = []
            async with websockets.connect(f"ws://127.0.0.1:{self.PORT}") as ws:
                await ws.send(json.dumps({"type": "hello", "protocol_version": "2", "message_id": "h-1",
                                          "supported_versions": ["2"]}))
                welcome = None
                while welcome is None:
                    message = json.loads(await asyncio.wait_for(ws.recv(), 5))
                    if message.get("type") == "welcome":
                        welcome = message
                for frame in frames:
                    await ws.send(json.dumps(frame))
                    while True:
                        message = json.loads(await asyncio.wait_for(ws.recv(), 5))
                        if message.get("in_reply_to") == frame["message_id"]:
                            replies.append(message)
                            break
            return welcome, replies

        return asyncio.run(run())

    @staticmethod
    def _rate(message_id, rating="good"):
        return {"type": "rate_card", "protocol_version": "2", "message_id": message_id,
                "session_id": "s1", "review_turn_id": "s1:c1:1", "card_id": "c1", "rating": rating,
                "review_commit_id": "s1:s1:c1:1"}

    def test_replay_with_new_message_ids_and_restart_is_deduplicated(self):
        self._start()
        welcome, replies = self._exchange([self._rate("m-1"), self._rate("m-2")])
        self.assertIn("review_commit_idempotency", welcome["capabilities"])
        self.assertEqual(["rating_saved", "rating_saved"], [r["type"] for r in replies])
        self.assertEqual([False, True], [r["duplicate"] for r in replies])
        self._stop()
        self._start()  # PC agent restart: only the commit table survives
        _, replies = self._exchange([self._rate("m-3"), self._rate("m-4", rating="easy")])
        self.assertEqual("rating_saved", replies[0]["type"])
        self.assertTrue(replies[0]["duplicate"])
        self.assertEqual("error", replies[1]["type"])
        self.assertEqual("COMMIT_CONFLICT", replies[1]["code"])
        with open(self.store) as handle:
            records = json.load(handle)["records"]
        self.assertEqual({"s1:s1:c1:1"}, set(records))
        self.assertEqual("APPLIED", records["s1:s1:c1:1"]["state"])
        self.assertEqual(1, records["s1:s1:c1:1"]["attempts"])  # exactly one Anki apply


if __name__ == "__main__":
    unittest.main(verbosity=2)
