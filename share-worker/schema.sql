CREATE TABLE IF NOT EXISTS picks (
  uuid TEXT PRIMARY KEY,
  picks TEXT NOT NULL,
  updated INTEGER NOT NULL
);

-- Public configs (configs.js).
CREATE TABLE IF NOT EXISTS authors (
  uuid TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  banned INTEGER NOT NULL DEFAULT 0,
  updated INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS configs (
  id TEXT PRIMARY KEY,
  author TEXT NOT NULL,
  name TEXT NOT NULL,
  name_key TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  tags TEXT NOT NULL DEFAULT '[]',
  data TEXT NOT NULL,
  enabled TEXT NOT NULL DEFAULT '[]',
  settings INTEGER NOT NULL DEFAULT 0,
  size INTEGER NOT NULL,
  client TEXT NOT NULL DEFAULT '',
  mc TEXT NOT NULL DEFAULT '',
  created INTEGER NOT NULL,
  updated INTEGER NOT NULL,
  downloads INTEGER NOT NULL DEFAULT 0,
  likes INTEGER NOT NULL DEFAULT 0,
  reports INTEGER NOT NULL DEFAULT 0,
  hidden INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS configs_author_name ON configs (author, name_key);
CREATE INDEX IF NOT EXISTS configs_updated ON configs (updated);
CREATE TABLE IF NOT EXISTS config_likes (
  id TEXT NOT NULL,
  uuid TEXT NOT NULL,
  PRIMARY KEY (id, uuid)
);
CREATE TABLE IF NOT EXISTS config_reports (
  id TEXT NOT NULL,
  uuid TEXT NOT NULL,
  reason TEXT NOT NULL,
  at INTEGER NOT NULL,
  PRIMARY KEY (id, uuid)
);
CREATE TABLE IF NOT EXISTS config_downloads (
  id TEXT NOT NULL,
  who TEXT NOT NULL,
  day INTEGER NOT NULL,
  PRIMARY KEY (id, who, day)
);
CREATE TABLE IF NOT EXISTS config_actions (
  uuid TEXT NOT NULL,
  at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS config_actions_uuid ON config_actions (uuid, at);
