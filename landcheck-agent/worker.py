"""Land check worker: takes the KML checks uploaded on the Fieldproof dashboard (Land
check tab), runs the land check agent on them and stores each report back, so it opens
from the dashboard.

  .venv\\Scripts\\python worker.py          runs until stopped (Ctrl+C)

DATABASE_URL points at the Fieldproof database; it defaults to the local one started by
docker compose. Earth Engine access comes from `earthengine authenticate` on this
computer.
"""
import collections
import json
import math
import os
import shutil
import sys
import tempfile
import threading
import time
import traceback

import psycopg

DATABASE_URL = os.environ.get("DATABASE_URL", "postgresql://fieldproof:fieldproof@localhost:5432/fieldproof")
POLL_SECONDS = 10


def heartbeat(stop):
    """Tells the dashboard the agent is running, every 30 seconds, even mid-check."""
    while not stop.is_set():
        try:
            with psycopg.connect(DATABASE_URL, autocommit=True) as c:
                c.execute("""INSERT INTO land_check_agent (id, seen_at) VALUES (1, now())
                             ON CONFLICT (id) DO UPDATE SET seen_at = now()""")
        except Exception as e:  # the next beat tries again
            print("heartbeat:", e, flush=True)
        stop.wait(30)


def claim(conn):
    """Takes the oldest waiting check, if any, and marks it as being checked."""
    return conn.execute("""
        UPDATE land_checks SET status = 'running', started_at = now(), error = '', progress = 0, parcels_done = 0
        WHERE id = (SELECT id FROM land_checks WHERE status = 'queued' ORDER BY created_at
                    FOR UPDATE SKIP LOCKED LIMIT 1)
        RETURNING id, name, file_name, kml, parcels""").fetchone()


class CheckStopped(Exception):
    pass


class Progress:
    """Saves how far a check is (0-100 %) every few seconds for the dashboard's bar.

    The agent reports real steps (a parcel's satellite numbers, each picture). Between
    steps the bar creeps on with the time a check usually takes, but never past the work
    actually under way, and it stays below 100 until the report is saved. It also notices
    when someone paused or deleted the check on the dashboard."""

    def __init__(self, check_id, parcels):
        import landcheck
        self.id = check_id
        self.t0 = time.time()
        self.state = {"units": 0, "total": parcels * landcheck.UNITS_PER_PARCEL, "done": 0, "started": 0, "parcels": parcels}
        # Parcels run four at a time, about five minutes a round, after a short start.
        self.expected = 60 + math.ceil(parcels / 4) * 300
        self.shown = 0
        self.stopped = False
        self.halt = threading.Event()
        self.thread = threading.Thread(target=self.loop, daemon=True)

    def update(self, state):  # called by the agent from its parcel threads
        self.state = state
        if self.stopped:
            raise CheckStopped()

    def percent(self):
        s = self.state
        real = s["units"] / s["total"]
        ceiling = s["started"] / s["parcels"]  # as if every parcel under way were finished
        by_time = (time.time() - self.t0) / self.expected
        pct = max(real, min(by_time, ceiling * 0.95))
        return max(1, min(99, int(pct * 100)))

    def loop(self):
        with psycopg.connect(DATABASE_URL, autocommit=True) as c:
            while not self.halt.is_set():
                pct = max(self.shown, self.percent())
                row = c.execute("UPDATE land_checks SET progress = %s, parcels_done = %s WHERE id = %s AND status = 'running' RETURNING id",
                                (pct, self.state["done"], self.id)).fetchone()
                self.shown = pct
                if not row:  # paused, stopped or deleted on the dashboard
                    self.stopped = True
                self.halt.wait(3)


def run(conn, check_id, name, file_name, kml, parcels):
    import landcheck  # loads Earth Engine; imported here so a sign-in problem shows on the check
    work = tempfile.mkdtemp(prefix="landcheck-")
    prog = Progress(check_id, parcels)
    prog.thread.start()
    try:
        src = os.path.join(work, "upload.kml")
        with open(src, "w", encoding="utf-8") as f:
            f.write(kml)
        out = os.path.join(work, "report")
        results = landcheck.check(src, out, title=name, progress=prog.update)
        with open(os.path.join(out, "report.html"), encoding="utf-8") as f:
            page = f.read()
        summary = collections.Counter(d["verdict"] for d in results)
        prog.halt.set()
        prog.thread.join()
        conn.execute("""UPDATE land_checks SET status = 'done', report = %s, summary = %s, finished_at = now(),
                               progress = 100, parcels_done = %s
                        WHERE id = %s AND status = 'running'""", (page, json.dumps(summary), len(results), check_id))
        print(f"done: {name} ({len(results)} parcels) {dict(summary)}", flush=True)
    finally:
        prog.halt.set()
        shutil.rmtree(work, ignore_errors=True)


def main():
    stop = threading.Event()
    threading.Thread(target=heartbeat, args=(stop,), daemon=True).start()
    with psycopg.connect(DATABASE_URL, autocommit=True) as conn:
        # A check left half-done (the worker was stopped) goes back in line.
        n = conn.execute("UPDATE land_checks SET status = 'queued', started_at = NULL WHERE status = 'running'").rowcount
        if n:
            print(f"{n} unfinished check(s) put back in line", flush=True)
        print("Land check worker running. Waiting for uploads...", flush=True)
        try:
            while True:
                job = claim(conn)
                if not job:
                    time.sleep(POLL_SECONDS)
                    continue
                check_id, name, file_name, kml, parcels = job
                print(f"checking: {name} ({file_name})", flush=True)
                try:
                    run(conn, check_id, name, file_name, kml, parcels)
                except CheckStopped:
                    print(f"stopped (paused or deleted on the dashboard): {name}", flush=True)
                except (Exception, SystemExit) as e:
                    traceback.print_exc()
                    msg = str(e).strip() or e.__class__.__name__
                    conn.execute("UPDATE land_checks SET status = 'failed', error = %s, finished_at = now() WHERE id = %s AND status = 'running'",
                                 (("The agent could not finish this check: " + msg)[:500], check_id))
        except KeyboardInterrupt:
            print("stopped", flush=True)
        finally:
            stop.set()


if __name__ == "__main__":
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    main()
