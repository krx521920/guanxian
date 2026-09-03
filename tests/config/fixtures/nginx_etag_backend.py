"""Synthetic HTTP peer/client for the isolated Nginx contract test; never deployed."""
import gzip
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import sys
from threading import Lock


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    versions = {}
    lock = Lock()

    def log_message(self, *_args):
        pass

    def respond(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length:
            self.rfile.read(length)
        status = 200
        with self.lock:
            version = self.versions.get(self.path, 0)
            if self.command == "PUT":
                if self.headers.get("If-Match") != f'"{version}"':
                    status = 412
                else:
                    version += 1
                    self.versions[self.path] = version
        data = {
            "version": version,
            "acceptEncoding": self.headers.get("Accept-Encoding"),
            "ifMatch": self.headers.get("If-Match"),
            "host": self.headers.get("Host"),
            "forwardedProto": self.headers.get("X-Forwarded-Proto"),
            "description": "synthetic member description " * 512,
        }
        body = json.dumps({"code": "OK" if status == 200 else "PRECONDITION_FAILED", "data": data}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Request-Id", "nginx-etag-contract")
        if not self.path.endswith("/missing"):
            prefix = "W/" if self.path.endswith("/weak") else ""
            self.send_header("ETag", f'{prefix}"{version}"')
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    do_GET = respond
    do_HEAD = respond
    do_PUT = respond


def request():
    arguments = json.loads(sys.argv[2])
    connection = HTTPConnection("web", 8080, timeout=3)
    try:
        connection.request(arguments["method"], arguments["path"],
                           body=arguments.get("body"), headers=arguments["headers"])
        response = connection.getresponse()
        headers = {key.lower(): value for key, value in response.getheaders()}
        body = response.read()
        if body and headers.get("content-encoding") == "gzip":
            body = gzip.decompress(body)
        result = {"status": response.status, "headers": headers, "body": body.decode()}
        print(json.dumps(result))
    finally:
        connection.close()


if __name__ == "__main__":
    if sys.argv[1] == "serve":
        ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
    elif sys.argv[1] == "request":
        request()
    else:
        raise SystemExit("expected serve or request")
