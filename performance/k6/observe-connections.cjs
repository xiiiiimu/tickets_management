'use strict';
// Diagnostic preload only. Forward all original calls; do not change listen options.
const net = require('node:net');
const readline = require('node:readline');
const {monitorEventLoopDelay, performance} = require('node:perf_hooks');
const histogram = monitorEventLoopDelay({resolution: 10}); histogram.enable();
const events = [], samples = [];
let server, accepted = 0, active = 0;
const nativeListen = process.binding('tcp_wrap').TCP.prototype.listen;
process.binding('tcp_wrap').TCP.prototype.listen = function(backlog) {
  const result = Reflect.apply(nativeListen, this, arguments);
  events.push({event: 'native_listen', epoch_ms: Date.now(), backlog, result});
  return result;
};
const emit = net.Server.prototype.emit;
net.Server.prototype.emit = function(event, ...args) {
  if (event === 'listening') {
    server = this;
    events.push({event, epoch_ms: Date.now(), address: this.address(), maxConnections: this.maxConnections ?? null,
      keepAliveTimeout: this.keepAliveTimeout, headersTimeout: this.headersTimeout,
      requestTimeout: this.requestTimeout, maxRequestsPerSocket: this.maxRequestsPerSocket});
  } else if (event === 'connection') {
    accepted++; active++;
    events.push({event: 'accepted', epoch_ms: Date.now(), remotePort: args[0].remotePort});
    args[0].once('close', () => active--);
  } else if (['close','error','drop'].includes(event)) {
    events.push({event, epoch_ms: Date.now(), code: args[0]?.code, message: args[0]?.message});
  }
  return Reflect.apply(emit, this, [event, ...args]);
};
const origin = performance.now(); let tick = 0;
setInterval(() => {
  samples.push({epoch_ms: Date.now(), listening: server?.listening, accepted, active,
    timer_lateness_ms: Math.max(0, performance.now() - origin - (++tick)*50)});
}, 50).unref();
readline.createInterface({input: process.stdin}).on('line', line => {
  if (line === 'diagnostics') process.stdout.write(JSON.stringify({kind: 'connection_diagnostics', events, samples,
    event_loop_delay_ms: {mean: histogram.mean/1e6, p95: histogram.percentile(95)/1e6,
      p99: histogram.percentile(99)/1e6, max: histogram.max/1e6}}) + '\n');
});
