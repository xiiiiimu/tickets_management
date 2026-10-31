'use strict';
// Independent fixture: request path performs no I/O except the HTTP response.
const http = require('node:http');
const readline = require('node:readline');
const body = Buffer.from('OK');
let requests = 0, finished = 0, connections = 0;
const server = http.createServer((req, res) => {
  requests++;
  req.resume();
  res.on('finish', () => finished++);
  res.writeHead(200, {'Content-Type': 'text/plain', 'Content-Length': body.length});
  res.end(body);
});
server.on('connection', () => connections++);
const snapshot = () => ({pid: process.pid, requests, finished, connections,
  cpu: process.cpuUsage(), memory: process.memoryUsage()});
readline.createInterface({input: process.stdin}).on('line', line => {
  if (line === 'snapshot') process.stdout.write(JSON.stringify(snapshot()) + '\n');
});
server.listen(Number(process.env.TINY_PORT), '127.0.0.1', () => {
  process.stdout.write(JSON.stringify({...snapshot(), ready: true}) + '\n');
});
