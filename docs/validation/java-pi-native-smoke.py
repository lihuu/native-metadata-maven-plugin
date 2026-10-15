import http.server
import json
import os
from pathlib import Path
import subprocess
import sys
import threading

BASE = Path(__file__).resolve().parent

def peer():
    for line in sys.stdin:
        message = json.loads(line)
        method = message.get('method')
        if 'id' not in message:
            continue
        if method == 'initialize':
            result = {'protocolVersion': message['params']['protocolVersion'],
                      'capabilities': {'tools': {}},
                      'serverInfo': {'name': 'dogfood-peer', 'version': '1'}}
        elif method == 'tools/list':
            result = {'tools': [{'name': 'echo', 'description': 'Return fixture payload',
                                  'inputSchema': {'type': 'object', 'properties': {}},
                                  'annotations': {'readOnlyHint': True}}]}
        elif method == 'tools/call':
            result = {'content': [{'type': 'text', 'text': 'MCP_DOGFOOD_PAYLOAD'},
                                  {'type': 'resource', 'resource': {
                                      'uri': 'fixture://dogfood/data', 'mimeType': 'text/plain',
                                      'text': 'EMBEDDED_DOGFOOD_PAYLOAD'}}],
                      'isError': False, 'structuredContent': {'marker': 'STRUCTURED_DOGFOOD_PAYLOAD'}}
        elif method == 'ping':
            result = {}
        else:
            print(json.dumps({'jsonrpc': '2.0', 'id': message['id'],
                              'error': {'code': -32601, 'message': 'Unsupported fixture method'}}), flush=True)
            continue
        print(json.dumps({'jsonrpc': '2.0', 'id': message['id'], 'result': result}), flush=True)

def smoke(binary, label):
    evidence = BASE / label
    evidence.mkdir(exist_ok=True)
    requests = []
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass
        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            requests.append(request)
            if len(requests) == 1:
                names = [t['function']['name'] for t in request['tools']]
                assert 'mcp__dogfood__echo' in names, names
                delta = {'role': 'assistant', 'tool_calls': [{'index': 0, 'id': 'dogfood-call',
                         'type': 'function', 'function': {'name': 'mcp__dogfood__echo', 'arguments': '{}'}}]}
                finish = 'tool_calls'
            else:
                content = json.dumps(request['messages'])
                assert 'MCP_DOGFOOD_PAYLOAD' in content, content
                assert 'EMBEDDED_DOGFOOD_PAYLOAD' in content, content
                assert 'STRUCTURED_DOGFOOD_PAYLOAD' in content, content
                delta = {'role': 'assistant', 'content': 'NATIVE_MCP_DOGFOOD_OK'}
                finish = 'stop'
            chunks = [dict(id='fixture', object='chat.completion.chunk', model='fixture',
                           choices=[{'index': 0, 'delta': delta, 'finish_reason': None}]),
                      dict(id='fixture', object='chat.completion.chunk', model='fixture',
                           choices=[{'index': 0, 'delta': {}, 'finish_reason': finish}])]
            body = ''.join('data: ' + json.dumps(c) + '\n\n' for c in chunks) + 'data: [DONE]\n\n'
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Content-Length', str(len(body.encode())))
            self.end_headers()
            self.wfile.write(body.encode())
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    home = evidence / 'home'
    config_dir = home / '.licode'
    config_dir.mkdir(parents=True, exist_ok=True)
    config = {'llm': {'baseUrl': f'http://127.0.0.1:{server.server_port}/v1',
                      'apiKey': 'fixture-key', 'model': 'fixture', 'contextWindowTokens': 100000},
              'mcp': {'servers': {'dogfood': {'command': sys.executable,
                          'args': [str(Path(__file__).resolve()), '--peer']}}}}
    config_path = config_dir / 'config.json'
    config_path.write_text(json.dumps(config))
    config_path.chmod(0o600)
    env = {k: v for k, v in os.environ.items()
           if not k.startswith(('OPENAI_', 'LICODE_', 'STORMIC_'))}
    env['HOME'] = str(home)
    env['NO_COLOR'] = '1'
    try:
        run = subprocess.run([str(Path(binary).resolve()), '-Duser.home=' + str(home),
                              'chat', '--yolo', '-p', 'Use the dogfood echo tool once.'],
                             cwd=evidence, env=env, capture_output=True, text=True, timeout=90)
        (evidence / 'stdout.txt').write_text(run.stdout)
        (evidence / 'stderr.txt').write_text(run.stderr)
        (evidence / 'requests.json').write_text(json.dumps(requests, indent=2))
        assert run.returncode == 0, (run.returncode, run.stdout, run.stderr)
        assert 'NATIVE_MCP_DOGFOOD_OK' in run.stdout, run.stdout
        assert len(requests) == 2, len(requests)
        print(label, 'PASS: native CLI -> loopback model -> stdio MCP -> structured/resource result -> final text')
    finally:
        server.shutdown()

if __name__ == '__main__':
    if sys.argv[1] == '--peer':
        peer()
    else:
        smoke(sys.argv[1], sys.argv[2])
