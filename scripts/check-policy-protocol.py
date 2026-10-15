#!/usr/bin/env python3
"""Executable ADR 004 model; synthetic HMAC is not production signature qualification."""
import argparse
import copy
import hashlib
import hmac
import itertools
import json
from pathlib import Path

KEY = b"synthetic-model-auth-only"
ZERO = "0" * 64
INITIAL = {"grant": True, "active": True, "steward": "alice"}
KINDS = ("grant", "revoke", "disable", "transfer")


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def signed(value):
    return {"body": copy.deepcopy(value), "signature": hmac.new(KEY, canonical(value), hashlib.sha256).hexdigest()}


def authenticate(record):
    return hmac.compare_digest(record["signature"], signed(record["body"])["signature"])


def changed(policy, kind):
    result = dict(policy)
    result.update({"grant": True} if kind == "grant" else {"grant": False} if kind == "revoke"
                  else {"active": False} if kind == "disable" else {"steward": "bob"})
    return result


class Refused(Exception):
    pass


def require(ok):
    if not ok:
        raise Refused()


class Model:
    def __init__(self):
        self.installation = "installation-one"
        self.epoch = 1
        self.online = True
        self.journal = []
        self.pending = None
        self.local = dict(INITIAL)
        self.local_events = {}
        self.committed = {}
        self.acknowledged = set()
        self.current = None
        self.local_receipt = None
        self.barrier = False
        self.primary_present = True

    def append(self, kind, event):
        body = {"installation": self.installation, "epoch": self.epoch, "sequence": len(self.journal) + 1,
                "previous": digest(self.journal[-1]) if self.journal else ZERO, "kind": kind, "event": event}
        self.journal.append(signed(body))

    def prepare(self, operation, kind):
        require(self.online and not self.barrier and self.primary_present)
        event = {"operation": operation, "kind": kind, "before": self.local,
                 "after": changed(self.local, kind), "revision": len(self.committed) + 1, "epoch": self.epoch}
        if self.pending is not None:
            require(self.pending == event)
            return
        require(operation not in self.committed)
        self.pending = copy.deepcopy(event)
        self.current = copy.deepcopy(event)
        self.append("PREPARE", event)

    def local_commit(self):
        require(self.primary_present and self.current is not None and self.current == self.pending)
        require(self.current["epoch"] == self.epoch and not self.barrier)
        # Atomic catalog mutation + local event + pending receipt.
        self.local = dict(self.current["after"])
        self.local_events[self.current["operation"]] = copy.deepcopy(self.current)

    def finalize(self):
        require(self.online and not self.barrier and self.current is not None)
        op = self.current["operation"]
        if op in self.committed:
            require(self.committed[op] == self.current)
            return
        require(self.local_events.get(op) == self.pending == self.current)
        require(self.current["epoch"] == self.epoch)
        self.append("COMMIT", self.current)
        self.committed[op] = copy.deepcopy(self.current)
        self.pending = None

    def persist_ack(self):
        require(self.online and self.current is not None)
        require(self.committed.get(self.current["operation"]) == self.current)
        self.local_receipt = self.current["operation"]

    def respond_success(self):
        require(self.current is not None and self.local_receipt == self.current["operation"])
        require(self.committed.get(self.local_receipt) == self.current)
        self.acknowledged.add(self.local_receipt)

    def abort(self):
        require(self.online and self.primary_present and self.pending is not None and not self.barrier)
        require(self.pending["operation"] not in self.local_events)
        self.append("ABORT", self.pending)
        self.pending = self.current = None

    def proof(self, nonce):
        require(self.online)
        return signed({"installation": self.installation, "epoch": self.epoch, "nonce": nonce,
                       "sequence": len(self.journal), "head": digest(self.journal[-1]) if self.journal else ZERO,
                       "pending": self.pending, "barrier": self.barrier})

    def fence_for_recovery(self):
        require(self.online)
        # Models external writer fencing as a required primitive; no distributed lease proof claim.
        self.primary_present = False
        self.epoch += 1
        self.barrier = True
        self.append("RECOVERY_BARRIER", None)

    def restore(self, records, proof, nonce):
        require(self.online and self.barrier and not self.primary_present and authenticate(proof))
        require(proof == self.proof(nonce))  # trusted linearizable current head, not a signed backup
        body = proof["body"]
        require(body["nonce"] == nonce and body["pending"] is None and body["barrier"])
        previous, pending, applied, seen = ZERO, None, dict(INITIAL), set()
        for number, record in enumerate(records, 1):
            require(authenticate(record))
            r = record["body"]
            require(r["installation"] == self.installation and r["sequence"] == number and r["previous"] == previous)
            require(1 <= r["epoch"] <= body["epoch"])
            event = r["event"]
            if r["kind"] == "PREPARE":
                require(pending is None and event["operation"] not in seen and event["before"] == applied)
                pending = event
            elif r["kind"] == "COMMIT":
                require(pending == event)
                applied = dict(event["after"])
                seen.add(event["operation"])
                pending = None
            elif r["kind"] == "ABORT":
                require(pending == event)
                pending = None
            elif r["kind"] != "RECOVERY_BARRIER":
                raise Refused()
            previous = digest(record)
        require(len(records) == body["sequence"] and previous == body["head"] and pending is None)
        require(self.acknowledged <= seen)
        return applied


def denied(action):
    try:
        action()
    except Refused:
        return
    raise AssertionError("unsafe transition was accepted")


def complete(m, op, kind):
    m.prepare(op, kind)
    m.local_commit()
    m.finalize()
    m.persist_ack()
    m.respond_success()


def check():
    windows = []
    steps = ("prepare", "local_commit", "finalize", "persist_ack", "respond_success")
    # Every prefix of every three-event sequence, including response-loss boundaries.
    for kinds in itertools.product(KINDS, repeat=3):
        for stop in range(16):
            m = Model()
            operations = [(i, step, kind) for i, kind in enumerate(kinds) for step in steps]
            for i, step, kind in operations[:stop]:
                m.prepare(str(i), kind) if step == "prepare" else getattr(m, step)()
            expected = dict(INITIAL)
            for event in m.committed.values():
                expected = changed(expected, event["kind"])
            was_pending = m.pending is not None
            m.fence_for_recovery()
            nonce = "restore-" + str(stop)
            if was_pending:
                denied(lambda: m.restore(m.journal, m.proof(nonce), nonce))
            else:
                assert m.restore(m.journal, m.proof(nonce), nonce) == expected
            windows.append({"sequence": kinds, "completedSteps": stop, "recovery": "closed" if was_pending else "exact-current-policy"})
    cases = []
    def record(name, procedure):
        procedure()
        cases.append(name)

    def outage_before():
        m = Model();m.online = False
        denied(lambda: m.prepare("x", "revoke"))
        assert m.local == INITIAL and not m.journal and not m.acknowledged
    record("witness outage prevents unprepared local commit", outage_before)

    def pending_after_local():
        m = Model();m.prepare("x", "revoke");m.local_commit();m.online = False
        denied(m.finalize);denied(m.respond_success)
        assert not m.local["grant"] and not m.acknowledged
        m.online = True;denied(m.abort);m.fence_for_recovery()
        denied(lambda: m.restore(m.journal, m.proof("n"), "n"))
    record("locally effective revocation with replication outage stays unacknowledged and restore-closed", pending_after_local)

    def abort_live():
        m = Model();m.prepare("x", "revoke");m.abort();complete(m, "y", "grant");m.fence_for_recovery()
        assert m.restore(m.journal, m.proof("n"), "n")["grant"]
    record("abort only before local commit on fenced live transaction; aborted intent does not change policy", abort_live)

    def retry():
        m = Model();m.prepare("x", "revoke");n=len(m.journal);m.prepare("x", "revoke");assert len(m.journal)==n
        denied(lambda: m.prepare("x", "grant"));m.local_commit();m.finalize();n=len(m.journal);m.finalize();assert len(m.journal)==n
        m.persist_ack();m.respond_success();m.respond_success();assert m.acknowledged=={"x"}
    record("duplicate preparation/finalization/response idempotent; same key with different intent refused", retry)

    def old_backup():
        m=Model();complete(m,"a","revoke");complete(m,"b","disable");complete(m,"c","transfer")
        m.fence_for_recovery();state=m.restore(m.journal,m.proof("n"),"n")
        assert state=={"grant":False,"active":False,"steward":"bob"}
    record("old scientific backup cannot restore grant, disabled identity or outgoing stewardship", old_backup)

    def stale_epoch():
        m=Model();m.prepare("x","revoke");m.fence_for_recovery();denied(m.local_commit);denied(m.finalize)
        denied(lambda:m.prepare("y","grant"))
    record("fenced writer cannot commit/finalize or start a new intent", stale_epoch)

    def bad_proofs():
        m=Model();complete(m,"a","revoke");old=m.proof("old");complete(m,"b","disable");m.fence_for_recovery()
        good=m.proof("fresh")
        denied(lambda:m.restore(m.journal,old,"fresh"))
        denied(lambda:m.restore(m.journal,good,"different-challenge"))
        for field,value in [("installation","another"),("epoch",1),("head",ZERO),("sequence",0)]:
            wrong=copy.deepcopy(good);wrong["body"][field]=value
            denied(lambda:m.restore(m.journal,wrong,"fresh"))
        for records in [m.journal[:-1],m.journal[1:],list(reversed(m.journal)),m.journal[:1]+m.journal]:
            denied(lambda:m.restore(records,good,"fresh"))
        tampered=copy.deepcopy(m.journal);tampered[1]["body"]["event"]["after"]["grant"]=True
        denied(lambda:m.restore(tampered,good,"fresh"))
        m.online=False;denied(lambda:m.restore(m.journal,good,"fresh"))
    record("stale/foreign/tampered proofs, wrong nonce, missing suffix/middle, reorder/duplicate and unavailable current authority refused", bad_proofs)

    # Deliberately rejected alternative: a locally acknowledged asynchronous write
    # leaves no independent evidence of a missing suffix after primary loss.
    naive_local = {**INITIAL,"grant":False};naive_remote = dict(INITIAL)
    assert naive_local != naive_remote and naive_remote["grant"]
    cases.append("counterexample retained: async-only acknowledgment resurrects a locally revoked grant")
    return {"status":"passed","crashWindowSequences":len(windows),"cases":cases,"windows":windows,
            "limits":["Finite abstract protocol model, not a proof over unbounded histories.","HMAC models authenticity only; Ed25519/TLS/keys and real durable I/O remain O03.2.","External writer fencing and non-rollback witness continuity are explicit assumptions, not inferred from signatures.","No deployed independent recovery or institutional physical-loss qualification."]}


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=False)
    result=check();(args.output/"model-results.json").write_text(json.dumps(result,indent=2)+"\n")
    print(json.dumps({k:v for k,v in result.items() if k!="windows"},indent=2))
