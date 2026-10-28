#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""A small client of mcx-agent for the examples: tool calls over MCP on HTTP (an agent in another container), MCP on
stdio, or the CLI, and the stream of pictures to POST /video. Standard library only; the chat bot carries its own
copy of MCP over HTTP, to stay one file."""

import http.client
import json
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request


class ToolError(Exception):
    """A tool failed: the text is "<code>: <message>"."""

    @property
    def code(self):
        return str(self).split(':', 1)[0]


class CliAgent:
    """Calls a tool with mcx-agent <tool> --<name> <value>...; the result is JSON on stdout."""

    def __init__(self, program='mcx-agent'):
        self.program = program

    def call(self, tool, **args):
        command = [self.program, tool]
        for name, value in args.items():
            command += [f'--{name}', str(value).lower() if isinstance(value, bool) else str(value)]
        run = subprocess.run(command, capture_output=True, text=True)
        try:
            result = json.loads(run.stdout) if run.stdout.strip() else {}
        except json.JSONDecodeError:
            result = {}
        if run.returncode:
            raise ToolError(f"{result.get('code', 'error')}: {result.get('error') or run.stderr.strip()}")
        return result


class McpAgent:
    """MCP (JSON-RPC 2.0, one message per line) with mcx-agent mcp on its stdin and stdout."""

    def __init__(self, program='mcx-agent', name='example'):
        self.server = subprocess.Popen([program, 'mcp'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        self.next_id = 0
        self.request(
            'initialize', protocolVersion='2025-06-18', capabilities={}, clientInfo={'name': name, 'version': '1'}
        )
        self.notify('notifications/initialized')

    def send(self, message):
        self.server.stdin.write(json.dumps(message) + '\n')
        self.server.stdin.flush()

    def notify(self, method):
        self.send({'jsonrpc': '2.0', 'method': method})

    def request(self, method, **params):
        self.next_id += 1
        self.send({'jsonrpc': '2.0', 'id': self.next_id, 'method': method, 'params': params})
        line = self.server.stdout.readline()
        if not line:
            raise ToolError('internal: the MCP server ended')
        reply = json.loads(line)
        if 'error' in reply:
            raise ToolError('internal: ' + reply['error']['message'])
        return reply['result']

    def call(self, tool, **args):
        result = self.request('tools/call', name=tool, arguments=args)
        if result.get('isError'):
            raise ToolError(result['content'][0]['text'])
        return result.get('structuredContent') or json.loads(result['content'][0]['text'])


class HttpMcpAgent(McpAgent):
    """MCP over streamable HTTP: each message a POST to the endpoint, with the bearer token."""

    def __init__(self, url, token, name='example'):
        self.url, self.token, self.next_id = url, token, 0
        self.request(
            'initialize', protocolVersion='2025-06-18', capabilities={}, clientInfo={'name': name, 'version': '1'}
        )
        self.notify('notifications/initialized')

    def post(self, message, timeout=330):
        request = urllib.request.Request(
            self.url,
            data=json.dumps(message).encode(),
            method='POST',
            headers={
                'Content-Type': 'application/json',
                'Accept': 'application/json, text/event-stream',
                'Authorization': 'Bearer ' + self.token,
                'MCP-Protocol-Version': '2025-06-18',
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                body = response.read()
        except urllib.error.HTTPError as error:
            raise ToolError(f'internal: HTTP {error.code}: {error.read().decode(errors="replace").strip()}') from error
        except OSError as error:
            raise ToolError(f'engine_not_ready: the MCP server is not reachable: {error}') from error
        return json.loads(body) if body else None

    def notify(self, method):
        self.post({'jsonrpc': '2.0', 'method': method})

    def request(self, method, **params):
        self.next_id += 1
        reply = self.post({'jsonrpc': '2.0', 'id': self.next_id, 'method': method, 'params': params})
        if 'error' in reply:
            raise ToolError('internal: ' + reply['error']['message'])
        return reply['result']


def connect(mcp_url=None, token_file=None, mcp=False, program='mcx-agent', name='example'):
    """The agent's tools: over HTTP (waiting for the daemon to answer), stdio MCP, or the CLI."""
    if not mcp_url:
        return McpAgent(program, name) if mcp else CliAgent(program)
    with open(token_file) as f:
        token = f.readline().strip()
    while True:
        try:
            return HttpMcpAgent(mcp_url, token, name)
        except ToolError as error:
            print(f'waiting: {error}', flush=True)
            time.sleep(2)


def wait_ready(agent, service):
    """Wait until the agent's service (mcdata, mcptt, mcvideo) is ready; its status."""
    while True:
        try:
            status = agent.call('status')
            if status['services'][service].get('ready'):
                return status
            print(f'waiting for {service}: {status["services"][service]}', flush=True)
        except ToolError as error:
            print(f'waiting: {error}', flush=True)
        time.sleep(2)


def stream_pictures(url, token, pictures, pace=False):
    """POST an iterable of I420 pictures (bytes) to the daemon's /video; its answer when the stream ends."""
    parts = urllib.parse.urlsplit(url)
    connection = http.client.HTTPConnection(parts.hostname, parts.port or 80, timeout=30)
    path = parts.path + ('?pace=true' if pace else '')
    connection.request(
        'POST',
        path,
        body=pictures,
        headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/octet-stream'},
        encode_chunked=True,
    )
    response = connection.getresponse()
    return json.loads(response.read() or b'{}')
