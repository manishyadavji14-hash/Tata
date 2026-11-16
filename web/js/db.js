// IndexedDB storage layer. Every piece of app data lives here so the app works fully offline.
const DB_NAME = 'abhyas-cbt';
const DB_VERSION = 1;

const STORES = {
  sets: { keyPath: 'id' },
  attempts: { keyPath: 'id', indexes: [['setId', 'setId'], ['startedAt', 'startedAt']] },
  bookmarks: { keyPath: 'key', indexes: [['setId', 'setId']] },
  topicStats: { keyPath: 'key' },
  settings: { keyPath: 'key' },
  chats: { keyPath: 'key' },
  importDrafts: { keyPath: 'key' },
};

let dbPromise = null;

function openDB() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      for (const [name, cfg] of Object.entries(STORES)) {
        if (db.objectStoreNames.contains(name)) continue;
        const store = db.createObjectStore(name, { keyPath: cfg.keyPath });
        for (const [idx, path] of cfg.indexes || []) store.createIndex(idx, path);
      }
    };
    req.onsuccess = () => {
      const db = req.result;
      db.onversionchange = () => db.close();
      resolve(db);
    };
    req.onerror = () => reject(req.error);
    req.onblocked = () => reject(new Error('Database is blocked by another open tab. Close other tabs of this app.'));
  });
  return dbPromise;
}

function promisify(req) {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function tx(storeName, mode, fn) {
  const db = await openDB();
  return new Promise((resolve, reject) => {
    const t = db.transaction(storeName, mode);
    const store = t.objectStore(storeName);
    let result;
    Promise.resolve(fn(store)).then((r) => { result = r; }, reject);
    t.oncomplete = () => resolve(result);
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error || new Error('Transaction aborted'));
  });
}

export const db = {
  get: (store, key) => tx(store, 'readonly', (s) => promisify(s.get(key))),
  put: (store, value) => tx(store, 'readwrite', (s) => promisify(s.put(value))),
  delete: (store, key) => tx(store, 'readwrite', (s) => promisify(s.delete(key))),
  all: (store) => tx(store, 'readonly', (s) => promisify(s.getAll())),
  byIndex: (store, index, value) => tx(store, 'readonly', (s) => promisify(s.index(index).getAll(value))),
  clear: (store) => tx(store, 'readwrite', (s) => promisify(s.clear())),
  putMany: (store, values) => tx(store, 'readwrite', (s) => Promise.all(values.map((v) => promisify(s.put(v))))),
};

// ---------- Settings (key/value) ----------
export const DEFAULT_SETTINGS = {
  theme: 'system',            // system | light | dark
  lang: 'both',               // hi | en | both
  provider: 'openai',         // openai | anthropic | gemini
  apiBase: 'https://api.openai.com/v1',
  apiKey: '',
  model: 'gpt-4o-mini',
  pdfMode: 'hybrid',          // hybrid | vision | text
  renderScale: 1.6,
  watermarks: '',             // extra watermark words, one per line
  autoSolve: true,
  temperature: 0.2,
  demoSeeded: false,
};

export async function getSettings() {
  const rows = await db.all('settings');
  const s = { ...DEFAULT_SETTINGS };
  for (const r of rows) s[r.key] = r.value;
  return s;
}

export async function saveSettings(patch) {
  await db.putMany('settings', Object.entries(patch).map(([key, value]) => ({ key, value })));
}

// ---------- Backup / restore of the whole database ----------
export async function exportAll() {
  const out = { app: 'abhyas-cbt', version: DB_VERSION, exportedAt: new Date().toISOString(), stores: {} };
  for (const name of Object.keys(STORES)) {
    if (name === 'importDrafts') continue;
    let rows = await db.all(name);
    if (name === 'settings') rows = rows.filter((r) => r.key !== 'apiKey'); // never export secrets
    out.stores[name] = rows;
  }
  return out;
}

export async function importAll(data) {
  if (!data || data.app !== 'abhyas-cbt' || !data.stores) throw new Error('यह Abhyas बैकअप फ़ाइल नहीं है / Not an Abhyas backup file');
  for (const [name, rows] of Object.entries(data.stores)) {
    if (!STORES[name] || !Array.isArray(rows)) continue;
    await db.putMany(name, rows);
  }
}
