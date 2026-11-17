// No browser or npm dependencies: exercise the exact injected JavaScript with provider stubs.
// Usage: node scripts/test-tv-player.cjs
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname,
  '../launcher/app/src/main/java/my/rafhan/app/tvhome/TvPlayer.java'), 'utf8');

function constant(name) {
  const declaration = source.match(new RegExp(`private static final String ${name} = ([\\s\\S]*?);\\n`));
  assert(declaration, name);
  // String literals, JSONObject.quote(CONSTANT) and other constants (+ MARK +) concatenated.
  const tokens = declaration[1].match(/JSONObject\.quote\(\w+\)|"(?:\\.|[^"\\])*"|\b[A-Z][A-Z_]+\b/g);
  return tokens.map(token => token.startsWith('JSONObject')
    ? JSON.stringify(constant(token.match(/\((\w+)\)/)[1]))
    : token.startsWith('"') ? JSON.parse(token) : constant(token)).join('');
}

const rtm = constant('SNAPSHOT'), tonton = constant('TONTON_SNAPSHOT');
const rtmSwitch = constant('RTM_SWITCH'), tontonSwitch = constant('TONTON_SWITCH');
function environment() {
  let clock = 100_000;
  const video = { readyState: 0, paused: true, muted: true, videoWidth: 1280, videoHeight: 720, currentSrc: 'blob:old',
    calls: 0, play() { this.calls++; return { catch() {} }; } };
  const context = { Date: { now: () => clock },
    location: { origin: 'https://watch.tonton.com.my', pathname: '/live/tv3' },
    document: { getElementById: () => ({}), querySelector: () => video } };
  context.window = context;
  vm.createContext(context);
  return { context, video, advance(ms) { clock += ms; },
    read(script, cap = 540, expected) {
      return JSON.parse(vm.runInContext(`${script}(${cap},${JSON.stringify(expected)})`, context));
    },
    switchTo(script, path) { return vm.runInContext(`${script}(${JSON.stringify(path)})`, context); } };
}

let checks = 0;
function test(name, body) { body(); checks++; console.log(`PASS ${name}`); }
function rtmEnvironment() {
  const e = environment();
  e.context.location = { origin: 'https://rtmklik.rtm.gov.my', pathname: '/live/tv/tv1' };
  const player = { on: false, plays: 0, chosen: [], current: { id: '1080', height: 1080 },
    isPlaying() { return this.on; }, unmute() {}, play() { this.plays++; },
    getPlaybackVideoData() { if (!this.on) throw Error('not ready'); return this.current; },
    getAvailableVideoQualities: () => [{ id: '480', height: 480 }, { id: '720', height: 720 }, { id: '1080', height: 1080 }],
    getVideoQuality() { return this.current; }, setVideoQuality(id) { this.chosen.push(id); } };
  e.context.gluePlayer = { player };
  return { ...e, player };
}

test('RTM chooses preview quality before playback/metadata', () => {
  const e = rtmEnvironment();
  const state = e.read(rtm);
  assert.equal(state.playing, false);
  assert.equal(state.player, true);
  assert.equal(state.video, true);
  assert.deepEqual(e.player.chosen, ['720']);
});
test('RTM rapid checks do not repeatedly restart play or quality switching', () => {
  const e = rtmEnvironment();
  for (let i = 0; i < 4; i++) { e.read(rtm); e.advance(250); }
  assert.equal(e.player.plays, 1);
  assert.deepEqual(e.player.chosen, ['720']);
  e.advance(1500);
  e.read(rtm);
  assert.deepEqual(e.player.chosen, ['720', '720']);
});
test('RTM readiness still waits for buffered video and valid shape', () => {
  const e = rtmEnvironment();
  e.player.on = true;
  assert.equal(e.read(rtm).playing, false);
  e.video.readyState = 3;
  assert.equal(e.read(rtm).playing, true);
  e.player.current = { id: '480', height: 480 };
  e.video.videoHeight = 11712;
  assert.equal(e.read(rtm).playing, false);
  assert.equal(e.context.__tvhomeBad['480'], 1);
});
test('RTM full screen restores automatic quality, retaining bad-shape fallback', () => {
  const e = rtmEnvironment();
  e.player.on = true;
  e.video.readyState = 3;
  e.read(rtm);
  e.read(rtm, 0);
  assert.equal(e.player.chosen.at(-1), 'auto');
  e.player.current = { id: '480', height: 480 };
  e.video.videoHeight = 11712;
  e.read(rtm, 0);
  assert.equal(e.player.chosen.at(-1), '720');
});
test('empty loading page is safe for both providers', () => {
  const e = environment();
  e.context.document.querySelector = () => null;
  assert.equal(e.read(rtm).playing, false);
  assert.equal(e.read(tonton).playing, false);
});

function tontonEnvironment() {
  const e = environment();
  const configuration = { abr: { enabled: true } };
  const player = { chosen: [], tracks: [{ id: 1, height: 540 }, { id: 2, height: 1080, active: true }],
    getVariantTracks() { return this.tracks; }, getConfiguration: () => configuration,
    configure(value) { Object.assign(configuration.abr, value.abr); },
    selectVariantTrack(track, clear) { assert.equal(clear, true); this.chosen.push(track.id); } };
  e.context.player = player;
  return { ...e, player, configuration };
}
test('Tonton chooses preview quality before playback', () => {
  const e = tontonEnvironment();
  assert.equal(e.read(tonton).playing, false);
  assert.deepEqual(e.player.chosen, [1]);
  assert.equal(e.configuration.abr.enabled, false);
});
test('Tonton rapid checks do not continually clear the media buffer', () => {
  const e = tontonEnvironment();
  for (let i = 0; i < 4; i++) { e.read(tonton); e.advance(250); }
  assert.deepEqual(e.player.chosen, [1]);
  assert.equal(e.video.calls, 1);
});
test('Tonton full screen restores adaptive bitrate', () => {
  const e = tontonEnvironment();
  e.read(tonton);
  e.read(tonton, 0);
  assert.equal(e.configuration.abr.enabled, true);
});
test('Tonton preserves login and media error detection', () => {
  const e = tontonEnvironment();
  e.context.location.pathname = '/login';
  assert.equal(e.read(tonton, 540, 'https://watch.tonton.com.my/live/tv3').signIn, true);
  e.context.location.pathname = '/live/tv3';
  e.video.error = { code: 3 };
  assert.equal(e.read(tonton).error, 3);
});
test('a still-playing previous document is never reported ready for the new channel', () => {
  const e = rtmEnvironment();
  e.player.on = true;
  e.video.readyState = 3;
  assert.equal(e.read(rtm, 540, 'https://rtmklik.rtm.gov.my/live/tv/tv2').playing, false);
  assert.equal(e.read(rtm, 540, 'https://rtmklik.rtm.gov.my/live/tv/tv1').playing, true);
  const t = tontonEnvironment();
  t.video.paused = false;
  t.video.readyState = 3;
  assert.equal(t.read(tonton, 540, 'https://watch.tonton.com.my/live/tv9').playing, false);
  assert.equal(t.read(tonton, 540, 'https://watch.tonton.com.my/live/tv3').playing, true);
});
test('RTM switch inside the page uses its router and marks the playing video', () => {
  const e = rtmEnvironment();
  e.player.on = true;
  e.video.readyState = 3;
  e.read(rtm);
  const pushed = [];
  e.context.next = { router: { push: path => pushed.push(path) } };
  assert.equal(e.switchTo(rtmSwitch, '/live/tv/tv2'), true);
  assert.deepEqual(pushed, ['/live/tv/tv2']);
  assert.equal(e.context.__tvhomeOldVideo, e.video);
  assert.equal(e.context.__tvhomePlayAt, 0);
  assert.equal(e.context.__tvhomeQuality, null);
  e.context.location = { origin: 'https://rtmklik.rtm.gov.my', pathname: '/live/tv/tv2' };
  const stale = e.read(rtm, 540, 'https://rtmklik.rtm.gov.my/live/tv/tv2');
  assert.equal(stale.stale, true); // the old channel still plays: not ready
  assert.equal(stale.playing, false);
  e.video.currentSrc = 'blob:new';
  assert.equal(e.read(rtm, 540, 'https://rtmklik.rtm.gov.my/live/tv/tv2').playing, true);
});
test('RTM switch refuses a page without the router', () => {
  const e = rtmEnvironment();
  assert.equal(e.switchTo(rtmSwitch, '/live/tv/tv2'), false);
  e.context.next = { router: {} };
  assert.equal(e.switchTo(rtmSwitch, '/live/tv/tv2'), false);
  assert.equal(e.context.__tvhomeOldVideo, undefined);
});
test('Tonton switch changes the address and waits for a new stream on the same video', () => {
  const t = tontonEnvironment();
  t.video.paused = false;
  t.video.readyState = 4;
  const pushed = [], events = [];
  t.context.history = { pushState: (s, title, path) => { pushed.push(path); t.context.location.pathname = path; } };
  t.context.PopStateEvent = function (type) { this.type = type; };
  t.context.dispatchEvent = event => events.push(event.type);
  assert.equal(t.switchTo(tontonSwitch, '/live/ntv7'), true);
  assert.deepEqual(pushed, ['/live/ntv7']);
  assert.deepEqual(events, ['popstate']);
  const expected = 'https://watch.tonton.com.my/live/ntv7';
  assert.equal(t.read(tonton, 540, expected).stale, true);
  t.video.currentSrc = '';
  t.video.readyState = 0;
  assert.equal(t.read(tonton, 540, expected).stale, true); // emptied, the new stream not attached yet
  t.video.currentSrc = 'blob:ntv7';
  t.video.readyState = 4;
  assert.equal(t.read(tonton, 540, expected).playing, true);
});
test('Tonton switch refuses pages other than a live channel', () => {
  const t = tontonEnvironment();
  t.context.location.pathname = '/login';
  assert.equal(t.switchTo(tontonSwitch, '/live/ntv7'), false);
  t.context.location = { origin: 'https://rtmklik.rtm.gov.my', pathname: '/live/tv/tv1' };
  assert.equal(t.switchTo(tontonSwitch, '/live/ntv7'), false);
});
console.log(`${checks} player script tests passed`);
