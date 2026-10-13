// Harness helpers; no cache scanning, browser attachment, or dependency installation.
const path = require('node:path');
const fs = require('node:fs');
const { pathToFileURL } = require('node:url');
const H = fs.realpathSync(process.env.HARNESS || path.join(__dirname, '..'));
exports.H = H;
exports.config = () => JSON.parse(fs.readFileSync(path.join(H, 'hillclimb.json'), 'utf8'));
exports.identifier = value => {
  if (typeof value !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_-]*$/.test(value)) throw new Error('Invalid identifier: ' + value);
  return value;
};
exports.localFile = (root, file) => {
  if (typeof file !== 'string' || !file || path.isAbsolute(file) || /^[a-z]+:/i.test(file)) throw new Error('Expected a relative specimen file');
  const realRoot = fs.realpathSync(root);
  const result = fs.realpathSync(path.resolve(root, file));
  if (path.relative(realRoot, result).split(path.sep).includes('..') || !fs.statSync(result).isFile()) throw new Error('Specimen escapes build or is not a file: ' + file);
  return result;
};
exports.url = file => {
  if (typeof file !== 'string' || !file) throw new Error('Missing specimen');
  if (/^https?:\/\//.test(file)) return file;
  const absolute = path.resolve(H, file);
  if (!fs.statSync(absolute).isFile()) throw new Error('Missing specimen: ' + file);
  return pathToFileURL(absolute).href;
};
exports.viewport = value => {
  if (!value || !Number.isInteger(value.width) || !Number.isInteger(value.height) || value.width < 1 || value.height < 1) throw new Error('Viewport needs positive integer width and height');
  return { width: value.width, height: value.height };
};
exports.inputs = (files, widths = '390,1440') => {
  const list = (files || '').split(';');
  const sizes = widths.split(',').map(Number);
  if (!list.length || list.some(f => !f.trim())) throw new Error('Supply a nonempty semicolon-separated file list');
  list.forEach(exports.url);
  if (!sizes.length || sizes.some(n => !Number.isInteger(n) || n < 1)) throw new Error('Widths must be positive integers');
  return { files: list, widths: sizes };
};
exports.launch = async () => {
  const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
  const executablePath = process.env.PLAYWRIGHT_EXECUTABLE_PATH;
  return chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
};
exports.fail = error => { console.error(error.message || error); process.exitCode = 1; };
