"""Controlled AI latency for A/B throughput tests; never substitutes for live-model validation."""
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        time.sleep(0.6)
        reply = json.dumps(dict(aiAvailable=True, scoreAdjustment=0, riskLevel=os.getenv('STUB_RISK_LEVEL', 'MEDIUM'),
                                confidence=0.9, reasons=['Controlled benchmark suspicion'],
                                suggestion='Keep under observation')).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(reply)))
        self.end_headers()
        self.wfile.write(reply)
        print(json.dumps({'time': time.time(), 'order_status': body['order'].get('status')}), flush=True)
    def log_message(self, *args):
        pass

ThreadingHTTPServer(('0.0.0.0', 8090), Handler).serve_forever()
