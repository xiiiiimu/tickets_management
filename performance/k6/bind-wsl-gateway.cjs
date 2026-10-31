'use strict';
// Startup-only binding override for the disposable fixture. Handler/source stay unchanged.
const net = require('node:net');
const target = process.env.TINY_BIND_HOST;
if (!target || net.isIP(target) !== 4 || target === '0.0.0.0' || target.startsWith('127.')) {
  throw new Error('Explicit validated WSL Windows gateway required');
}
const listen = net.Server.prototype.listen;
net.Server.prototype.listen = function(...args) {
  if (Number(args[0]) === 18089 && args[1] === '127.0.0.1') args[1] = target;
  return Reflect.apply(listen, this, args);
};
