import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const read = file => readFileSync(file, 'utf8');

test('admin hosting has an explicitly named target and cannot deploy another site accidentally', () => {
  const config = JSON.parse(read('firebase.json'));
  assert.equal(config.hosting.target, 'gallipick-admin');
  assert.equal(config.hosting.public, 'firebase/admin-web');
  assert.ok(!config.hosting.rewrites);
  assert.equal(config.functions.source, 'firebase/functions');
});

test('admin UI is view-only and never embeds credentials or direct Firestore queries', () => {
  const html = read('firebase/admin-web/index.html');
  const app = read('firebase/admin-web/app.js');
  const cfg = read('firebase/admin-web/config.js');
  assert.match(html, /lang="ko"/);
  assert.match(html, /<main\b/);
  assert.match(html, /로그아웃/);
  assert.match(html, /읽기 전용 관리자 화면/);
  assert.match(app, /getAdminOverview/);
  assert.match(app, /gallipickAdmin/);
  assert.match(app, /httpsCallable/);
  assert.doesNotMatch(app, /(?:deleteDoc|updateDoc|addDoc|setDoc|query\()/);
  assert.match(cfg, /apiKey:\s*""/);
  assert.match(cfg, /appId:\s*""/);
  assert.match(cfg, /gallipick-production/);
});
