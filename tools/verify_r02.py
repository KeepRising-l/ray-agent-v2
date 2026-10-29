"""本机 R02 集成验证：真实 PostgreSQL 隔离 schema + 模拟模型服务，不输出凭据。"""
import http.client
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
props = {}
for line in (ROOT / "application-local.properties").read_text(encoding="utf-8").splitlines():
    if "=" in line and not line.lstrip().startswith("#"):
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
schema = "r02_check_" + uuid.uuid4().hex
assert re.fullmatch(r"r02_check_[a-f0-9]{32}", schema)

def sql(command):
    subprocess.run(["wsl", "--exec", "docker", "exec", "pgvector-db", "psql", "-U", "postgres", "-d", "ray_agent_v2", "-c", command], check=True, capture_output=True)

requests_seen = []
class Mock(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass
    def handle(self):
        # 取消和强制中断用例会主动重置连接，这不是模拟服务的测试失败。
        try:
            super().handle()
        except (ConnectionResetError, BrokenPipeError):
            pass
    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        requests_seen.append(request)
        text = request["messages"][-1]["content"]
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        self.wfile.flush()
        try:
            if text in ("slow", "cancel", "crash"):
                time.sleep(3)
            data = {"choices": [{"index": 0, "delta": {"content": "answer"}, "finish_reason": None}]}
            self.wfile.write(("data: " + json.dumps(data) + "\n\n").encode())
            self.wfile.flush()
            if text == "break":
                return
            self.wfile.write(b'data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n')
            self.wfile.flush()
        except OSError:
            pass

def free_port():
    with socket.socket() as value:
        value.bind(("127.0.0.1", 0))
        return value.getsockname()[1]

port = free_port()
model = ThreadingHTTPServer(("127.0.0.1", 0), Mock)
threading.Thread(target=model.serve_forever, daemon=True).start()
env = os.environ.copy()
env.update(SPRING_DATASOURCE_URL=props["spring.datasource.url"] + "?currentSchema=" + schema,
           RAY_MODEL_BASE_URL=f"http://127.0.0.1:{model.server_port}", RAY_MODEL_API_KEY="test-only",
           RAY_MODEL_MODEL="mock", RAY_MODEL_EXTRA_CHANNELS="")
java_home = os.environ.get("JAVA_HOME")
if not java_home:
    settings = subprocess.run(["java", "-XshowSettings:properties", "-version"], capture_output=True, text=True)
    match = re.search(r"java\.home\s*=\s*(.+)", settings.stderr)
    if not match:
        raise RuntimeError("cannot locate the JDK")
    java_home = match.group(1).strip()
java = Path(java_home) / "bin" / "java.exe"
process = None
logs = tempfile.TemporaryFile()

def start():
    global process
    process = subprocess.Popen([str(java), "-jar", str(ROOT / "ray-agent-server/target/ray-agent-server-0.1.0-SNAPSHOT.jar"),
                                f"--server.port={port}", "--ray.run.timeout-ms=1500"], cwd=ROOT, env=env,
                               stdout=logs, stderr=subprocess.STDOUT)
    for _ in range(150):
        if process.poll() is not None:
            raise RuntimeError("backend startup failed; check local logs")
        try:
            get("/api/sessions")
            return
        except Exception:
            time.sleep(.1)
    raise RuntimeError("backend startup timeout")

def get(path):
    with urllib.request.urlopen(f"http://127.0.0.1:{port}" + path, timeout=5) as response:
        return json.load(response)

def post(path, body):
    request = urllib.request.Request(f"http://127.0.0.1:{port}" + path, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=8) as response:
        return response.read().decode()

def stream(session, text, key):
    return post("/api/chat/stream", {"message": text, "sessionId": session, "clientRequestId": key})

def open_stream(session, text, key):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=8)
    connection.request("POST", "/api/chat/stream", body=json.dumps({"message": text, "sessionId": session, "clientRequestId": key}), headers={"Content-Type": "application/json"})
    response = connection.getresponse()
    while True:
        line = response.readline().decode()
        if line.startswith("data:"):
            meta = json.loads(line[5:])
            if "runId" in meta:
                return connection, response, meta["runId"]
        if not line:
            raise RuntimeError("missing run metadata")

try:
    sql("CREATE SCHEMA " + schema)
    start()
    session = json.loads(post("/api/sessions", {"title": "R02 test"}))["id"]
    assert "event:done" in stream(session, "first", "first")
    assert "event:done" in stream(session, "second", "second")
    assert len(requests_seen[-1]["messages"]) == 4  # 系统、前一问答、本次问题
    count = len(requests_seen)
    try:
        stream(session, "second", "second")
        raise AssertionError("duplicate accepted")
    except urllib.error.HTTPError as error:
        assert error.code == 409
    assert len(requests_seen) == count
    assert "event:error" in stream(session, "break", "break")
    assert get(f"/api/sessions/{session}/runs")[0]["status"] == "FAILED"
    assert not get(f"/api/sessions/{session}/messages")[-1]["complete"]
    connection, response, run_id = open_stream(session, "cancel", "cancel")
    try:
        try:
            stream(session, "parallel", "parallel")
            raise AssertionError("parallel request accepted")
        except urllib.error.HTTPError as error:
            assert error.code == 409
        assert json.loads(post(f"/api/runs/{run_id}/cancel", {}))["status"] == "CANCELLED"
        assert json.loads(post(f"/api/runs/{run_id}/cancel", {}))["status"] == "CANCELLED"
    finally:
        connection.close()
    assert "event:error" in stream(session, "slow", "slow")
    run = get(f"/api/sessions/{session}/runs")[0]
    assert run["status"] == "TIMED_OUT"
    events = get(f"/api/runs/{run['id']}/events")
    assert [event["seq"] for event in events] == list(range(1, len(events) + 1))
    connection, response, crash = open_stream(session, "crash", "crash")
    process.kill()
    process.wait(timeout=10)
    connection.close()
    start()
    assert get(f"/api/runs/{crash}")["status"] == "INTERRUPTED"
    assert len(get(f"/api/sessions/{session}/messages")) >= 6
    print("R02 PostgreSQL integration: history/context, duplicate rejection, failure, cancel, timeout, events and restart passed")
finally:
    if process is not None and process.poll() is None:
        process.terminate()
        process.wait(timeout=10)
    model.shutdown()
    model.server_close()
    logs.close()
    sql("DROP SCHEMA " + schema + " CASCADE")
