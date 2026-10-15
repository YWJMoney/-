// 기기 안 저장소. 거래는 지우지 않는 이벤트 로그(add/update/delete)로 쌓고 화면은 그 투영을 본다.
export const SCHEMA_VERSION = 1;
const KEY = 'jangbu.v1';

export function uid() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
}

export function emptyState() {
  return {
    schema: SCHEMA_VERSION,
    log: [],
    cards: [],
    obligations: [],
    rules: {},
    notified: {},
    settings: { payday: 25, monthlyBudget: 0, theme: 'auto', largeText: false, onboarded: false },
  };
}

// 스키마 버전별 마이그레이션. 새 버전이 생기면 여기에 단계를 추가한다.
const MIGRATIONS = {
  // 2: (s) => ({ ...s, schema: 2 }),
};

export function migrate(raw) {
  let s = { ...emptyState(), ...raw, settings: { ...emptyState().settings, ...(raw?.settings || {}) } };
  while ((s.schema || 1) < SCHEMA_VERSION) {
    const next = (s.schema || 1) + 1;
    s = MIGRATIONS[next](s);
  }
  return s;
}

export function project(log) {
  const map = new Map();
  for (const ev of log) {
    if (ev.op === 'add') map.set(ev.data.id, { ...ev.data });
    else if (ev.op === 'update' && map.has(ev.id)) map.set(ev.id, { ...map.get(ev.id), ...ev.data });
    else if (ev.op === 'delete' && map.has(ev.id)) map.set(ev.id, { ...map.get(ev.id), deleted: true });
  }
  return [...map.values()].sort((a, b) => ((b.date || '') + (b.time || '')).localeCompare((a.date || '') + (a.time || '')));
}

export function createStore(storage = globalThis.localStorage) {
  let state;
  try {
    const raw = storage?.getItem(KEY);
    state = raw ? migrate(JSON.parse(raw)) : emptyState();
  } catch {
    state = emptyState();
  }
  let txs = project(state.log);
  const listeners = new Set();

  const save = () => {
    try {
      storage?.setItem(KEY, JSON.stringify(state));
    } catch (e) {
      console.warn('저장 실패', e);
    }
    txs = project(state.log);
    listeners.forEach((fn) => fn());
  };
  const push = (ev) => state.log.push({ at: new Date().toISOString(), ...ev });

  return {
    get state() {
      return state;
    },
    get txs() {
      return txs;
    },
    view() {
      return { ...state, txs };
    },
    subscribe(fn) {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },
    addTx(tx) {
      const data = { id: uid(), createdAt: new Date().toISOString(), ...tx };
      push({ op: 'add', data });
      save();
      return data;
    },
    addTxs(list) {
      const out = list.map((tx) => {
        const data = { id: uid(), createdAt: new Date().toISOString(), ...tx };
        push({ op: 'add', data });
        return data;
      });
      save();
      return out;
    },
    updateTx(id, patch) {
      push({ op: 'update', id, data: patch });
      save();
    },
    deleteTx(id) {
      push({ op: 'delete', id });
      save();
    },
    upsert(listName, item) {
      const list = state[listName];
      const i = list.findIndex((x) => x.id === item.id);
      if (i >= 0) list[i] = { ...list[i], ...item };
      else list.push({ id: uid(), ...item });
      save();
    },
    remove(listName, id) {
      state[listName] = state[listName].filter((x) => x.id !== id);
      save();
    },
    setSettings(patch) {
      state.settings = { ...state.settings, ...patch };
      save();
    },
    setRule(merchant, category) {
      if (!merchant) return;
      state.rules[merchant] = category;
      save();
    },
    markNotified(key, date) {
      state.notified[key] = date;
      save();
    },
    replaceAll(next) {
      state = migrate(next);
      save();
    },
    reset() {
      state = emptyState();
      save();
    },
  };
}
