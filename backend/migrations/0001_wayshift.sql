CREATE TABLE feedback (
  id TEXT PRIMARY KEY, category TEXT NOT NULL, message TEXT NOT NULL, reply_email TEXT,
  lat REAL, lon REAL, actor TEXT NOT NULL, created INTEGER NOT NULL,
  mailed INTEGER, attempts INTEGER NOT NULL DEFAULT 0, next_attempt INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX feedback_mail ON feedback(mailed, next_attempt);
CREATE TABLE incidents (
  id TEXT PRIMARY KEY, kind TEXT NOT NULL, road TEXT NOT NULL, lat REAL NOT NULL,
  lon REAL NOT NULL, heading REAL NOT NULL, author TEXT NOT NULL, created INTEGER NOT NULL,
  expires INTEGER NOT NULL, confirmed INTEGER, removed INTEGER
);
CREATE INDEX incident_active ON incidents(road, expires, removed);
CREATE INDEX incident_author ON incidents(author, created);
CREATE TABLE incident_votes (
  incident TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
  actor TEXT NOT NULL, vote TEXT NOT NULL, created INTEGER NOT NULL,
  PRIMARY KEY(incident, actor)
);
CREATE TABLE blocked_reporters (actor TEXT PRIMARY KEY);
CREATE TABLE abuse (incident TEXT NOT NULL, actor TEXT NOT NULL, created INTEGER NOT NULL, PRIMARY KEY(incident, actor));
CREATE TABLE subscriptions (token TEXT PRIMARY KEY, actor TEXT NOT NULL, status TEXT NOT NULL, expires INTEGER NOT NULL, checked INTEGER NOT NULL);
CREATE INDEX subscription_actor ON subscriptions(actor);
CREATE TABLE ad_challenges (nonce TEXT PRIMARY KEY, actor TEXT NOT NULL, expires INTEGER NOT NULL);
CREATE TABLE ad_events (transaction_id TEXT PRIMARY KEY, nonce TEXT NOT NULL UNIQUE, actor TEXT NOT NULL, created INTEGER NOT NULL);
CREATE TABLE reward_sessions (id TEXT PRIMARY KEY, actor TEXT NOT NULL, device TEXT NOT NULL, started INTEGER NOT NULL, finished INTEGER);
CREATE UNIQUE INDEX reward_active ON reward_sessions(actor) WHERE finished IS NULL;
