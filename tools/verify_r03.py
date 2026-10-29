"""R03 本机验收：隔离 PostgreSQL schema；默认模拟模型，--live 使用本机真实模型/Tavily。

不输出配置、密钥或模型原始响应。默认模式仍会抓取 example.com，需能访问公网。
"""
import argparse
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
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--live", action="store_true", help="使用真实本机兼容模型、Tavily 和网页")
parser.add_argument("--browser", action="store_true", help="用本机 Playwright/Chrome 验证页面和刷新后的工具来源")
args = parser.parse_args()
props = {}
for line in (ROOT / "application-local.properties").read_text(encoding="utf-8").splitlines():
    if "=" in line and not line.lstrip().startswith("#"):
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()

schema = "r03_check_" + uuid.uuid4().hex
assert re.fullmatch(r"r03_check_[a-f0-9]{32}", schema)


def sql(command):
    subprocess.run(["wsl", "--exec", "docker", "exec", "pgvector-db", "psql", "-U", "postgres",
                    "-d", "ray_agent_v2", "-c", command], check=True, capture_output=True)


def free_port():
    with socket.socket() as value:
        value.bind(("127.0.0.1", 0))
        return value.getsockname()[1]


seen = []
class Mock(BaseHTTPRequestHandler):
    def log_message(self, *unused):
        pass

    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        seen.append(request)
        user = next(m["content"] for m in reversed(request["messages"]) if m["role"] == "user")
        observation = request["messages"][-1] if request["messages"][-1]["role"] == "tool" else None
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()

        def packet(delta, reason=None):
            data = {"choices": [{"index": 0, "delta": delta, "finish_reason": reason}]}
            self.wfile.write(("data: " + json.dumps(data) + "\n\n").encode())
            self.wfile.flush()

        try:
            if user == "cancel":
                time.sleep(5)
            if observation and user != "budget":
                value = json.loads(observation["content"])
                if user == "fetch":
                    assert value["success"] and "documentation examples" in value["content"]
                    assert value["sources"][0]["id"] == "W1"
                    answer = "Example Domain 用于文档示例。[W1]"
                else:
                    assert not value["success"] and not value["sources"]
                    answer = "工具失败，没有取得网页依据。"
                packet({"content": answer})
                packet({}, "stop")
            elif user in ("fetch", "blocked", "invalid", "budget"):
                arguments = {"url": "https://example.com/" if user == "fetch" else "http://127.0.0.1/secret"}
                if user == "invalid":
                    arguments = {"url": 1, "extra": "rejected"}
                raw = json.dumps(arguments)
                # 参数跨两个 SSE 片段，验证实际 HTTP 解析和组装，而不只测完整 JSON。
                packet({"tool_calls": [{"index": 0, "id": "call-" + str(len(seen)), "type": "function",
                                         "function": {"name": "web_fetch", "arguments": raw[:8]}}]})
                packet({"tool_calls": [{"index": 0, "function": {"arguments": raw[8:]}}]})
                packet({}, "tool_calls")
            else:
                packet({"content": "普通回答"})
                packet({}, "stop")
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
        except (OSError, AssertionError):
            pass


port = free_port()
model = ThreadingHTTPServer(("127.0.0.1", 0), Mock)
threading.Thread(target=model.serve_forever, daemon=True).start()
env = os.environ.copy()
env["SPRING_DATASOURCE_URL"] = props["spring.datasource.url"] + "?currentSchema=" + schema
env["RAY_MODEL_EXTRA_CHANNELS"] = ""
if not args.live:
    env.update(RAY_MODEL_BASE_URL=f"http://127.0.0.1:{model.server_port}", RAY_MODEL_MODEL="mock", RAY_MODEL_API_KEY="test-only")
settings = subprocess.run(["java", "-XshowSettings:properties", "-version"], capture_output=True, text=True)
java = Path(re.search(r"java\.home\s*=\s*(.+)", settings.stderr).group(1).strip()) / "bin/java.exe"
process = None
frontend = None
log = tempfile.TemporaryFile()


def get(path):
    with urllib.request.urlopen(f"http://127.0.0.1:{port}" + path, timeout=5) as response:
        return json.load(response)


def post(path, body):
    request = urllib.request.Request(f"http://127.0.0.1:{port}" + path, data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=100) as response:
        return response.read().decode()


def packets(text):
    result = []
    for block in text.split("\n\n"):
        data = [line[5:].strip() for line in block.splitlines() if line.startswith("data:")]
        event = next((line[6:].strip() for line in block.splitlines() if line.startswith("event:")), "message")
        if data:
            result.append((event, json.loads("\n".join(data))))
    return result


def run(text):
    session = json.loads(post("/api/sessions", {"title": "R03 verification"}))["id"]
    output = packets(post("/api/chat/stream", {"sessionId": session, "clientRequestId": str(uuid.uuid4()), "message": text}))
    meta = next(data for event, data in output if event == "meta")
    events = get("/api/runs/" + meta["runId"] + "/events")
    state = get("/api/runs/" + meta["runId"])
    assert [event["seq"] for event in events] == list(range(1, len(events) + 1))
    return output, events, state


try:
    sql("CREATE SCHEMA " + schema)
    process = subprocess.Popen([str(java), "-jar", str(ROOT / "ray-agent-server/target/ray-agent-server-0.1.0-SNAPSHOT.jar"),
                                f"--server.port={port}", "--logging.file.name=logs/r03-verify.log",
                                "--ray.run.timeout-ms=90000", "--ray.run.max-model-steps=4", "--ray.run.max-tool-calls=4"],
                               cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    for _ in range(200):
        if process.poll() is not None:
            raise RuntimeError("backend failed to start; inspect ignored logs/r03-verify.log")
        try:
            get("/api/sessions")
            break
        except Exception:
            time.sleep(.1)
    else:
        raise RuntimeError("backend startup timeout")

    if args.browser:
        frontend_port = free_port()
        frontend_env = env.copy()
        frontend_env["RAY_DEV_API_TARGET"] = f"http://127.0.0.1:{port}"
        frontend = subprocess.Popen(["node", str(ROOT / "ray-agent-web/node_modules/vite/bin/vite.js"),
                                     "--host", "127.0.0.1", "--port", str(frontend_port), "--strictPort"],
                                    cwd=ROOT / "ray-agent-web", env=frontend_env, stdout=log, stderr=subprocess.STDOUT)
        browser_env = os.environ.copy()
        if not browser_env.get("NODE_PATH"):
            browser_env["NODE_PATH"] = str(Path.home() / ".cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules")
        subprocess.run(["node", str(ROOT / "tools/verify_r03_browser.cjs"), f"http://127.0.0.1:{frontend_port}",
                        "live" if args.live else "mock"], cwd=ROOT, env=browser_env, check=True)
    elif args.live:
        prompts = [
            "请调用 web_fetch 抓取 https://example.com/ ，仅根据抓取正文用中文说明这个网站的用途，引用来源编号。",
            "请调用 web_search 搜索 Tavily 官方文档中的 Search API，回答 include_answer 参数的用途，引用本次搜索返回的来源编号。不要凭记忆回答。",
        ]
        for index, prompt in enumerate(prompts):
            output, events, state = run(prompt)
            succeeded = [json.loads(event["detail"]) for event in events if event["type"] == "TOOL_SUCCEEDED"]
            assert state["status"] == "SUCCEEDED", state["errorMessage"]
            expected = "web_fetch" if index == 0 else "web_search"
            assert any(event["name"] == expected and event["sources"] for event in succeeded)
            answer = "".join(data["text"] for event, data in output if event == "text")
            assert re.search(r"\[W\d+\]", answer)
            print("PASS real model -> " + expected + " -> observation -> cited answer -> PostgreSQL")
    else:
        for prompt in ("plain", "fetch", "blocked", "invalid", "budget"):
            before = len(seen)
            output, events, state = run(prompt)
            names = [event["type"] for event in events]
            if prompt == "budget":
                assert state["status"] == "FAILED" and "预算" in state["errorMessage"]
                assert len(seen) - before == 4
            else:
                assert state["status"] == "SUCCEEDED", state["errorMessage"]
                assert len(seen) - before == (1 if prompt == "plain" else 2)
            if prompt == "fetch":
                assert "TOOL_SUCCEEDED" in names
                assert any(event == "text" and "[W1]" in data["text"] for event, data in output)
            elif prompt in ("blocked", "invalid"):
                assert "TOOL_FAILED" in names and "TOOL_SUCCEEDED" not in names
            print("PASS mock model + real PostgreSQL: " + prompt)

        session = json.loads(post("/api/sessions", {"title": "R03 cancellation"}))["id"]
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
        connection.request("POST", "/api/chat/stream", body=json.dumps({"sessionId": session, "message": "cancel"}),
                           headers={"Content-Type": "application/json"})
        response = connection.getresponse()
        while True:
            line = response.readline().decode()
            if line.startswith("data:") and "runId" in line:
                run_id = json.loads(line[5:])["runId"]
                break
        state = json.loads(post("/api/runs/" + run_id + "/cancel", {}))
        assert state["status"] == "CANCELLED"
        connection.close()
        print("PASS explicit cancellation terminates the agent run")
finally:
    if frontend and frontend.poll() is None:
        frontend.terminate()
        frontend.wait(timeout=10)
    if process and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
    model.shutdown()
    model.server_close()
    sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE")
    log.close()
